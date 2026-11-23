package com.martin.ruby.voice

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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest

/** A Kokoro voice Ruby can use. [sid] is its speaker number in the model. */
enum class KokoroSpeaker(val sid: Int, val label: String) {
    HEART(3, "Heart"),
    NICOLE(6, "Nicole"),
}

/**
 * The Kokoro voice model (Kokoro-82M v1.0, int8, packaged by sherpa-onnx): downloaded once,
 * over Wi-Fi, checked, and unpacked into no_backup, which Android's backup never copies.
 * Nothing about the user is sent; it's a plain download from GitHub.
 */
object KokoroModel {

    /** sherpa-onnx's "tts-models" release on GitHub. */
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"

    /** The SHA-256 GitHub lists for that file; anything else is refused. */
    const val SHA256 = "72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653"
    const val DOWNLOAD_BYTES = 132_353_410L

    /** The folder inside the archive. */
    const val ARCHIVE_DIR = "kokoro-int8-multi-lang-v1_0"

    const val MODEL = "model.int8.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val LEXICON_US = "lexicon-us-en.txt"

    /** What's unpacked: what English needs, the British lexicon for later voices, and the license. */
    val KEEP = setOf(MODEL, VOICES, TOKENS, LEXICON_US, "lexicon-gb-en.txt", "LICENSE", "README.md")

    private val REQUIRED = listOf(MODEL, VOICES, TOKENS, LEXICON_US)
    private const val WORK_NAME = "kokoro-download"

    /** Where the model lives once installed. */
    fun dir(context: Context) = File(context.noBackupFilesDir, "kokoro")

    fun isInstalled(context: Context): Boolean = isInstalled(dir(context))

    fun isInstalled(dir: File): Boolean = REQUIRED.all { File(dir, it).isFile }

    /** Starts the download (Wi-Fi only), or keeps one that's already going. */
    fun download(context: Context) {
        val request = OneTimeWorkRequestBuilder<KokoroDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Deletes the installed model (Android text-to-speech is used instead). */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) { dir(context).deleteRecursively() }

    /** Where things stand, for the Voice settings. */
    sealed interface State {
        data object NotInstalled : State
        /** Waiting for Wi-Fi, or downloading ([fraction] 0..1, or null before it starts). */
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
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED ->
                    State.Downloading(null, waitingForWifi = true)
                info.state == WorkInfo.State.RUNNING ->
                    State.Downloading(info.progress.getFloat(KokoroDownloadWorker.PROGRESS, -1f).takeIf { it >= 0 }, false)
                info.state == WorkInfo.State.FAILED ->
                    State.Failed(info.outputData.getString(KokoroDownloadWorker.ERROR) ?: "The download failed.")
                else -> State.NotInstalled // cancelled, or succeeded but then removed
            }
        }

    /**
     * Downloads, checks and installs the model. [onProgress] gets 0..1. Throws [IOException]
     * with a message for the user if anything's wrong; nothing is left half-installed.
     *
     * [archive] installs from a file already on the phone instead of downloading (the device
     * test uses it, since the emulator can't reach GitHub through this PC's antivirus). It's
     * checked exactly like a download.
     */
    suspend fun install(context: Context, archive: File? = null, onProgress: suspend (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = dir(context)
        val parent = target.parentFile!!
        val download = File(parent, "kokoro-download.part")
        val unpacking = File(parent, "kokoro-unpacking")
        try {
            val file = if (archive != null) {
                check(archive)
                onProgress(1f)
                archive
            } else {
                fetch(download, onProgress)
                download
            }
            unpacking.deleteRecursively()
            file.inputStream().buffered().use { unpack(it, unpacking) }
            for (name in REQUIRED) if (!File(unpacking, name).isFile) throw IOException("The download is missing $name.")
            target.deleteRecursively()
            if (!unpacking.renameTo(target)) throw IOException("Couldn't move the voice into place.")
        } finally {
            download.delete()
            unpacking.deleteRecursively()
        }
    }

    /** Refuses [file] unless it's exactly the expected download. */
    private fun check(file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) Unit
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (file.length() != DOWNLOAD_BYTES || got != SHA256) {
            throw IOException("That file isn't the expected Kokoro download, so it wasn't used.")
        }
    }

    /** Downloads [URL] to [into], checking size and SHA-256 as it goes. */
    private suspend fun fetch(into: File, onProgress: suspend (Float) -> Unit) {
        val connection = URI(URL).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode} for the voice download.")
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
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != DOWNLOAD_BYTES || got != SHA256) {
                throw IOException("The download didn't match the expected file, so it wasn't used. Try again.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unpacks the files in [KEEP] from a .tar.bz2 [archive] into [dest]. Anything else is
     * skipped, including entries outside [ARCHIVE_DIR] or with paths that try to climb out.
     */
    fun unpack(archive: InputStream, dest: File) {
        dest.mkdirs()
        val root = dest.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(archive)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (!entry.isFile) continue
                val name = entry.name.removePrefix("./")
                val file = name.removePrefix("$ARCHIVE_DIR/")
                if (file == name || file.contains('/') || file !in KEEP) continue
                val out = File(root, file).canonicalFile
                if (out.parentFile != root) continue
                out.outputStream().buffered().use { tar.copyTo(it) }
            }
        }
    }
}

/** Downloads and installs the Kokoro model in the background, reporting progress. */
class KokoroDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SensingLog.add("Kokoro voice: downloading (126 MB).")
        KokoroModel.install(applicationContext) { setProgress(workDataOf(PROGRESS to it)) }
        SensingLog.add("Kokoro voice: installed.")
        VoiceSession.kokoroInstalled(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val why = e.message ?: e.javaClass.simpleName
        SensingLog.add("Kokoro voice download failed: $why")
        Result.failure(workDataOf(ERROR to why))
    }

    companion object {
        const val PROGRESS = "progress"
        const val ERROR = "error"
    }
}
