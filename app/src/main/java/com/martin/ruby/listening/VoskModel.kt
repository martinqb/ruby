package com.martin.ruby.listening

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * The on-phone listening model for the wake word and talking over Ruby: Vosk's small US English
 * model (vosk-model-small-en-us-0.15, Apache 2.0). Downloaded once over Wi-Fi from Alpha
 * Cephei, checked, and unpacked into no_backup. Nothing about the user is sent.
 */
object VoskModel {

    const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    const val DOWNLOAD_BYTES = 41_205_931L
    const val DOWNLOAD_MB = 41

    /** The folder inside the zip. */
    const val ARCHIVE_DIR = "vosk-model-small-en-us-0.15"

    /** The word list lives in this file (an OpenFst symbol table). */
    private const val WORDS_FILE = "graph/Gr.fst"
    private val REQUIRED = listOf("am/final.mdl", "conf/model.conf", "graph/HCLr.fst", WORDS_FILE, "ivector/final.ie")
    private const val WORK_NAME = "vosk-download"

    fun dir(context: Context) = File(context.noBackupFilesDir, "vosk-en")

    fun isInstalled(context: Context): Boolean = isInstalled(dir(context))

    fun isInstalled(dir: File): Boolean = REQUIRED.all { File(dir, it).isFile }

    fun download(context: Context) {
        val request = OneTimeWorkRequestBuilder<VoskDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    sealed interface State {
        data object NotInstalled : State
        data class Downloading(val fraction: Float?, val waitingForWifi: Boolean) : State
        data object Installed : State
        data class Failed(val why: String) : State
    }

    fun state(context: Context): Flow<State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
            val info = infos.firstOrNull()
            when {
                isInstalled(context) -> State.Installed
                info == null -> State.NotInstalled
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> State.Downloading(null, waitingForWifi = true)
                info.state == WorkInfo.State.RUNNING ->
                    State.Downloading(info.progress.getFloat(VoskDownloadWorker.PROGRESS, -1f).takeIf { it >= 0 }, false)
                info.state == WorkInfo.State.FAILED -> State.Failed(info.outputData.getString(VoskDownloadWorker.ERROR) ?: "The download failed.")
                else -> State.NotInstalled
            }
        }

    /** Downloads (or takes [archive]), checks and installs the model; nothing is left half-installed. */
    suspend fun install(context: Context, archive: File? = null, onProgress: suspend (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = dir(context)
        val parent = target.parentFile!!
        val download = File(parent, "vosk-download.part")
        val unpacking = File(parent, "vosk-unpacking")
        try {
            val file = archive?.also { check(it) } ?: download.also { fetch(it, onProgress) }
            unpacking.deleteRecursively()
            file.inputStream().buffered().use { unpack(it, unpacking) }
            for (name in REQUIRED) if (!File(unpacking, name).isFile) throw IOException("The download is missing $name.")
            target.deleteRecursively()
            if (!unpacking.renameTo(target)) throw IOException("Couldn't move the listening model into place.")
        } finally {
            download.delete()
            unpacking.deleteRecursively()
        }
    }

    private fun check(file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) Unit
        }
        if (file.length() != DOWNLOAD_BYTES || hex(digest.digest()) != SHA256) {
            throw IOException("That file isn't the expected listening model, so it wasn't used.")
        }
    }

    private suspend fun fetch(into: File, onProgress: suspend (Float) -> Unit) {
        val connection = URI(URL).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != 200) throw IOException("The model site answered ${connection.responseCode}.")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReported = -1
            DigestInputStream(connection.inputStream, digest).use { input ->
                into.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > DOWNLOAD_BYTES) throw IOException("The download is bigger than expected.")
                        val percent = (done * 100 / DOWNLOAD_BYTES).toInt()
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(percent / 100f)
                        }
                    }
                }
            }
            if (done != DOWNLOAD_BYTES || hex(digest.digest()) != SHA256) {
                throw IOException("The download didn't match the expected file, so it wasn't used. Try again.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Unpacks the model folder from the zip into [dest], refusing paths that climb out of it. */
    fun unpack(archive: InputStream, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        ZipInputStream(archive).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val inside = entry.name.removePrefix("$ARCHIVE_DIR/")
                if (inside == entry.name) continue
                val out = File(root, inside).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) continue
                out.parentFile?.mkdirs()
                out.outputStream().buffered().use { zip.copyTo(it) }
            }
        }
    }

    /**
     * Which of [words] the model knows. Its word list is stored in [WORDS_FILE] as
     * length-prefixed strings, so each word is looked for as its 4-byte length then its letters.
     */
    suspend fun knownWords(context: Context, words: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        val file = File(dir(context), WORDS_FILE)
        if (!file.isFile || words.isEmpty()) return@withContext emptySet()
        val patterns = words.distinct().associateWith { w ->
            val letters = w.toByteArray()
            ByteBuffer.allocate(4 + letters.size).order(ByteOrder.LITTLE_ENDIAN).putInt(letters.size).put(letters).array()
        }
        val found = mutableSetOf<String>()
        val longest = patterns.values.maxOf { it.size }
        RandomAccessFile(file, "r").use { raf ->
            val chunk = ByteArray(1 shl 20)
            var carry = ByteArray(0)
            while (found.size < patterns.size) {
                val n = raf.read(chunk)
                if (n <= 0) break
                val window = carry + chunk.copyOf(n)
                for ((word, p) in patterns) if (word !in found && indexOf(window, p) >= 0) found += word
                carry = window.copyOfRange(maxOf(0, window.size - longest + 1), window.size)
            }
        }
        found
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}

class VoskDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SensingLog.add("Listening model: downloading (${VoskModel.DOWNLOAD_MB} MB).")
        VoskModel.install(applicationContext) { setProgress(workDataOf(PROGRESS to it)) }
        SensingLog.add("Listening model: installed.")
        Listening.modelInstalled()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val why = e.message ?: e.javaClass.simpleName
        SensingLog.add("Listening model download failed: $why")
        Result.failure(workDataOf(ERROR to why))
    }

    companion object {
        const val PROGRESS = "progress"
        const val ERROR = "error"
    }
}
