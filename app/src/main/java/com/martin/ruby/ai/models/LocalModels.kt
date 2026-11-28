package com.martin.ruby.ai.models

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.content.edit
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * On-phone model files: downloading (Android's download manager, which resumes and can wait for
 * Wi-Fi), checking them against their SHA-256, deleting, free space and the phone's memory.
 * Files live in Ruby's own folder on the phone's storage, deleted with the app.
 */
object LocalModels {

    sealed interface State {
        data object NotDownloaded : State
        data class Downloading(val done: Long, val total: Long?, val waitingForWifi: Boolean) : State
        data object Checking : State
        data object Ready : State
        data class Failed(val why: String) : State
    }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun dir(context: Context): File =
        (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).also { it.mkdirs() }

    fun file(context: Context, model: LocalModel) = File(dir(context), model.fileName)
    private fun partFile(context: Context, model: LocalModel) = File(dir(context), model.fileName + ".part")

    fun freeBytes(context: Context): Long = StatFs(dir(context).path).availableBytes

    fun totalRam(context: Context): Long = memoryInfo(context).totalMem
    fun availableRam(context: Context): Long = memoryInfo(context).availMem

    private fun memoryInfo(context: Context) = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }

    fun fit(context: Context, model: LocalModel): RamFit? {
        val bytes = model.bytes ?: file(context, model).takeIf { it.exists() }?.length() ?: return null
        return RamCheck.fit(bytes, totalRam(context), availableRam(context))
    }

    private fun prefs(context: Context) = context.getSharedPreferences("local_models", Context.MODE_PRIVATE)

    private fun verified(context: Context, model: LocalModel) = prefs(context).getBoolean("verified_${model.id}", false)

    /** Starts (or resumes) downloading [model]. Returns a problem to show, or null. */
    fun download(context: Context, model: LocalModel, wifiOnly: Boolean): String? {
        val app = context.applicationContext
        val need = (model.bytes ?: 0L) + 500_000_000L
        if (model.bytes != null && freeBytes(app) < need) {
            return "Not enough free space: it needs about ${RamCheck.gb(need)}, and ${RamCheck.gb(freeBytes(app))} is free."
        }
        val manager = app.getSystemService(DownloadManager::class.java)
        partFile(app, model).delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Ruby: ${model.name}")
            .setDescription("An AI model for Ruby to run on the phone")
            .setDestinationUri(Uri.fromFile(partFile(app, model)))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        val id = manager.enqueue(request)
        prefs(app).edit { putLong("download_${model.id}", id).putBoolean("verified_${model.id}", false) }
        SensingLog.add("Models: downloading ${model.name}" + if (wifiOnly) " (Wi-Fi only)." else ".")
        _states.update { it + (model.id to State.Downloading(0, model.bytes, waitingForWifi = false)) }
        return null
    }

    /** Stops a download and deletes the model's files. */
    fun delete(context: Context, model: LocalModel) {
        val app = context.applicationContext
        val id = prefs(app).getLong("download_${model.id}", -1)
        if (id >= 0) app.getSystemService(DownloadManager::class.java).remove(id)
        file(app, model).delete()
        partFile(app, model).delete()
        prefs(app).edit { remove("download_${model.id}").remove("verified_${model.id}") }
        SensingLog.add("Models: deleted ${model.name}.")
        _states.update { it + (model.id to State.NotDownloaded) }
    }

    /**
     * Looks at every model's download and file again (the Models screen calls this every second
     * while it's open, and the chat before using a model). A finished download is checked, then ready.
     */
    suspend fun refresh(context: Context, models: List<LocalModel>) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val manager = app.getSystemService(DownloadManager::class.java)
        for (model in models) {
            if (_states.value[model.id] == State.Checking) continue
            val id = prefs(app).getLong("download_${model.id}", -1)
            val state: State = when {
                file(app, model).exists() && verified(app, model) -> State.Ready
                file(app, model).exists() && model.sha256 == null -> State.Ready
                id >= 0 -> downloadState(app, manager, id, model)
                else -> State.NotDownloaded
            }
            _states.update { it + (model.id to state) }
        }
    }

    private suspend fun downloadState(app: Context, manager: DownloadManager, id: Long, model: LocalModel): State {
        manager.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return State.NotDownloaded
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: model.bytes
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> finish(app, model)
                DownloadManager.STATUS_FAILED -> {
                    prefs(app).edit { remove("download_${model.id}") }
                    State.Failed("The download failed (reason $reason). Try again.")
                }
                DownloadManager.STATUS_PAUSED -> State.Downloading(
                    done, total,
                    waitingForWifi = reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK,
                )
                else -> State.Downloading(done, total, waitingForWifi = false)
            }
        }
    }

    /** A finished download: check its size and SHA-256, then move it into place. */
    private suspend fun finish(app: Context, model: LocalModel): State {
        val part = partFile(app, model)
        val target = file(app, model)
        if (!part.exists() && target.exists()) return if (verified(app, model) || model.sha256 == null) State.Ready else verify(app, model, target)
        if (!part.exists()) return State.Failed("The downloaded file is missing. Try again.")
        if (model.bytes != null && part.length() != model.bytes) {
            part.delete()
            prefs(app).edit { remove("download_${model.id}") }
            return State.Failed("The download was the wrong size. Try again.")
        }
        target.delete()
        if (!part.renameTo(target)) return State.Failed("Couldn't move the downloaded file into place.")
        prefs(app).edit { remove("download_${model.id}") }
        return if (model.sha256 == null) State.Ready else verify(app, model, target)
    }

    private suspend fun verify(app: Context, model: LocalModel, file: File): State {
        _states.update { it + (model.id to State.Checking) }
        val got = withContext(Dispatchers.IO) { sha256(file) }
        return if (got.equals(model.sha256, ignoreCase = true)) {
            prefs(app).edit { putBoolean("verified_${model.id}", true) }
            SensingLog.add("Models: ${model.name} downloaded and checked.")
            State.Ready
        } else {
            file.delete()
            SensingLog.add("Models: ${model.name}'s checksum didn't match; deleted.")
            State.Failed("The downloaded file didn't match its checksum, so it was deleted. Try again.")
        }.also { s -> _states.update { it + (model.id to s) } }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun isReady(context: Context, model: LocalModel): Boolean =
        file(context, model).exists() && (model.sha256 == null || verified(context, model))
}
