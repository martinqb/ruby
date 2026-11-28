package com.martin.ruby.ai.models

import android.content.Context
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Runs an on-phone model: llama.cpp's llama-server (shipped inside Ruby), started for one model
 * at a time and reached only from the phone itself (127.0.0.1, with a random key each start, so
 * other apps can't use it). It's stopped when another engine is chosen, another model is loaded,
 * or after [IDLE_MS] without use, to give the memory back.
 */
object LocalServer {

    sealed interface Status {
        data object Off : Status
        data class Loading(val model: String) : Status
        data class Running(val model: String) : Status
        data class Failed(val why: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var process: Process? = null
    private var modelId: String? = null
    private var key: String = ""
    private var idle: Job? = null

    const val PORT = 18_080
    private const val IDLE_MS = 15 * 60_000L
    private const val LOAD_TIMEOUT_MS = 240_000L

    fun baseUrl() = "http://127.0.0.1:$PORT/v1"
    fun apiKey() = key

    private fun program(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libllama_server_exe.so")

    /** Whether this build has llama.cpp for this phone (arm64 phones; not the x86 emulator). */
    fun available(context: Context) = program(context).exists()

    /** Makes sure [model] is loaded and answering. Throws [IllegalStateException] with a reason to show. */
    suspend fun ensure(context: Context, model: LocalModel) = lock.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (modelId == model.id && process?.isAlive == true && healthy()) {
                touch()
                return@withContext
            }
            stopLocked()
            if (!available(app)) throw IllegalStateException("On-phone models need an arm64 phone; this device can't run them.")
            val file = LocalModels.file(app, model)
            if (!LocalModels.isReady(app, model)) throw IllegalStateException("${model.name} isn't downloaded yet. Download it on the Models screen.")
            key = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val log = File(app.cacheDir, "llama-server.log")
            val libs = app.applicationInfo.nativeLibraryDir
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8) - 1
            val command = listOf(
                program(app).path,
                "-m", file.path,
                "--host", "127.0.0.1", "--port", PORT.toString(),
                "--api-key", key,
                "-c", "8192",
                "-t", threads.toString(),
                "--jinja",
                "--reasoning-budget", "0",
                "--no-webui",
            )
            _status.value = Status.Loading(model.name)
            SensingLog.add("Models: loading ${model.name} on the phone.")
            val started = System.currentTimeMillis()
            process = ProcessBuilder(command)
                .directory(File(libs))
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { environment()["LD_LIBRARY_PATH"] = libs }
                .start()
            modelId = model.id
            while (System.currentTimeMillis() - started < LOAD_TIMEOUT_MS) {
                val p = process
                if (p == null || !p.isAlive) {
                    val why = lastLines(log)
                    stopLocked()
                    _status.value = Status.Failed("The model stopped while loading. $why")
                    throw IllegalStateException("${model.name} couldn't load on the phone. $why".trim())
                }
                if (healthy()) {
                    SensingLog.add("Models: ${model.name} loaded in ${(System.currentTimeMillis() - started) / 1000} s.")
                    _status.value = Status.Running(model.name)
                    touch()
                    return@withContext
                }
                delay(500)
            }
            stopLocked()
            _status.value = Status.Failed("Loading took too long.")
            throw IllegalStateException("${model.name} took too long to load.")
        }
    }

    /** Stops the model and frees its memory. */
    suspend fun stop() = lock.withLock { withContext(Dispatchers.IO) { stopLocked() } }

    /** For callers outside coroutines (switching engines). */
    fun stopSoon() {
        scope.launch { stop() }
    }

    private fun stopLocked() {
        idle?.cancel()
        process?.let { p ->
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            SensingLog.add("Models: on-phone model stopped (memory freed).")
        }
        process = null
        modelId = null
        _status.value = Status.Off
    }

    /** Used again: put off the idle stop. */
    fun touch() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            SensingLog.add("Models: on-phone model unused for 15 minutes; stopping it.")
            stop()
        }
    }

    private fun healthy(): Boolean = try {
        val c = URL("http://127.0.0.1:$PORT/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1_000
        c.readTimeout = 2_000
        c.setRequestProperty("Authorization", "Bearer $key")
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    private fun lastLines(log: File): String = try {
        log.readLines().takeLast(3).joinToString(" ").take(300)
    } catch (e: Exception) {
        ""
    }
}
