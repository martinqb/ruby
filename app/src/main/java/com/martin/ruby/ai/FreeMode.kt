package com.martin.ruby.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Free mode: Gemini Nano, running on the phone through Android's AICore (ML Kit GenAI Prompt
 * API). Free, offline and private, but:
 * - It only answers while Ruby is in the foreground ([Failed.background]), so work that starts
 *   in the background (the car-connect briefing, nightly summaries) can't use it.
 * - It can't use tools, so reminders go through [FreeReminders].
 * - It's less careful with facts than Claude.
 */
object FreeMode {

    /** Whether Gemini Nano is on this phone. */
    sealed interface Status {
        data object Checking : Status
        data object Ready : Status
        /** Supported, but the model isn't on the phone yet. */
        data object Downloadable : Status
        data class Downloading(val fraction: Float?) : Status
        data object NotOnThisPhone : Status
        data class Problem(val why: String) : Status
    }

    /** Gemini Nano refused or failed; [message] is written for the user. */
    class Failed(message: String, val code: Int? = null, cause: Throwable? = null) : Exception(message, cause) {
        /** Refused because Ruby wasn't in the foreground. */
        val background get() = code == BACKGROUND_USE_BLOCKED
    }

    /** ML Kit's error code for "Background usage is blocked". */
    const val BACKGROUND_USE_BLOCKED = 30

    private val _status = MutableStateFlow<Status>(Status.Checking)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Mutex()
    private var model: GenerativeModel? = null

    private suspend fun model(): GenerativeModel = lock.withLock {
        model ?: Generation.getClient(
            GenerationConfig.builder().apply {
                modelConfig = ModelConfig.builder().apply {
                    releaseStage = ModelReleaseStage.STABLE
                    preference = ModelPreference.FULL
                }.build()
            }.build()
        ).also { model = it }
    }

    /** Checks (quickly) whether Gemini Nano is ready, and updates [status]. */
    suspend fun refresh(): Status {
        val now = try {
            when (model().checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.Ready
                FeatureStatus.DOWNLOADABLE -> Status.Downloadable
                FeatureStatus.DOWNLOADING -> Status.Downloading(null)
                else -> Status.NotOnThisPhone
            }
        } catch (e: Exception) {
            // Phones without AICore or ML Kit's parts end up here too.
            Status.NotOnThisPhone.takeIf { e is GenAiException && e.errorCode in NOT_SUPPORTED_CODES }
                ?: Status.Problem(e.message ?: e.javaClass.simpleName)
        }
        if (_status.value !is Status.Downloading || now !is Status.Downloadable) _status.value = now
        return now
    }

    /** Ready to answer right now (checks if it doesn't know yet). */
    suspend fun ready(): Boolean = (_status.value.takeIf { it == Status.Ready } ?: refresh()) == Status.Ready

    /**
     * Downloads the model (gigabytes; AICore may wait for Wi-Fi, charging or idle). Only when
     * the user asks. Progress goes to [status].
     */
    suspend fun download() {
        var total = 0L
        _status.value = Status.Downloading(null)
        SensingLog.add("Free mode: downloading Gemini Nano.")
        try {
            model().download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadStarted -> total = s.bytesToDownload
                    is DownloadStatus.DownloadProgress ->
                        _status.value = Status.Downloading(if (total > 0) (s.totalBytesDownloaded.toFloat() / total).coerceIn(0f, 1f) else null)
                    is DownloadStatus.DownloadCompleted -> SensingLog.add("Free mode: Gemini Nano downloaded.")
                    is DownloadStatus.DownloadFailed -> {
                        SensingLog.add("Free mode: download failed (${s.e.errorCode}): ${s.e.message}")
                        _status.value = Status.Problem("The download didn't work: ${s.e.message}")
                    }
                    else -> Unit
                }
            }
        } finally {
            refresh()
        }
    }

    /**
     * Gemini Nano's answer to [prompt], with [system] instructions. [onText] gets the answer so
     * far as it arrives. Throws [Failed].
     */
    suspend fun write(system: String, prompt: String, maxTokens: Int = 1_000, onText: ((String) -> Unit)? = null): String {
        val request = generateContentRequest(SystemInstruction(system), TextPart(prompt)) { maxOutputTokens = maxTokens }
        val text = try {
            val m = model()
            if (onText == null) {
                m.generateContent(request).candidates.firstOrNull()?.text
            } else {
                val soFar = StringBuilder()
                m.generateContent(request, object : StreamingCallback {
                    override fun onNewText(text: String) {
                        soFar.append(text)
                        onText(soFar.toString())
                    }
                }).candidates.firstOrNull()?.text ?: soFar.toString()
            }
        } catch (e: GenAiException) {
            if (e.errorCode in NOT_SUPPORTED_CODES) _status.value = Status.NotOnThisPhone
            throw Failed(describe(e), e.errorCode, e)
        }
        return text?.trim().orEmpty().ifEmpty { throw Failed("Free mode sent back nothing.") }
    }

    private fun describe(e: GenAiException) = when (e.errorCode) {
        BACKGROUND_USE_BLOCKED -> "Free mode only works while Ruby is open."
        8 -> "Free mode's model isn't on this phone yet."
        9 -> "Free mode is busy. Try again in a moment."
        12 -> "That's too long for Free mode."
        27 -> "Free mode has used its battery allowance for now."
        in NOT_SUPPORTED_CODES -> AiEngine.NOT_ON_THIS_PHONE
        else -> "Free mode couldn't answer (error ${e.errorCode})."
    }

    /** NOT_SUPPORTED, NEEDS_SYSTEM_UPDATE, AICORE_INCOMPATIBLE. */
    private val NOT_SUPPORTED_CODES = setOf(16, 604, -101)
}
