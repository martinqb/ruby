package com.martin.ruby.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.martin.ruby.log.SensingLog

/**
 * Ruby's ears: turning what the user says into text. Like [RubyVoice], the app only uses
 * this, so another recognizer (e.g. a wake word plus an on-device model) can replace
 * [AndroidSpeechEars] later.
 *
 * Calls come from the main thread, and so do the [Listener] callbacks.
 */
interface RubyEars {

    interface Listener {
        /** The words heard so far, while the user is still talking. */
        fun onPartial(text: String)

        /** What the user said, once they've stopped. */
        fun onResult(text: String)

        /** Nothing usable was heard; [message] is written for the user. */
        fun onError(message: String)
    }

    /** False if the phone has no speech recognizer at all. */
    val available: Boolean

    /** True if recognition happens on the phone, so audio isn't sent anywhere. */
    val onDevice: Boolean

    /** How loud the user is right now while listening, 0..1, or -1 if unknown. Main thread. */
    val level: Float get() = -1f

    /** Listens until the user stops talking. Needs the RECORD_AUDIO permission. */
    fun listen(listener: Listener)

    /** Stops listening and gives the result for what was heard so far. */
    fun stop()

    /** Stops listening without a result. */
    fun cancel()

    fun close()
}

/**
 * Android's speech recognizer. It uses the phone's on-device recognizer when there is one
 * (Android 12+), so audio stays on the phone. Otherwise it uses the phone's default
 * recognition service, asking it to work offline if it can.
 */
class AndroidSpeechEars(context: Context) : RubyEars {

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override var level = -1f
        private set

    /** Set when the on-device recognizer failed for want of a language model; then use the default. */
    private var onDeviceFailed = false

    private val hasOnDevice: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) && !onDeviceFailed

    override val available: Boolean
        get() = hasOnDevice || SpeechRecognizer.isRecognitionAvailable(appContext)

    override val onDevice: Boolean
        get() = hasOnDevice

    override fun listen(listener: RubyEars.Listener) {
        cancel()
        if (!available) {
            listener.onError("This phone has no speech recognizer.")
            return
        }
        val useOnDevice = hasOnDevice
        val r = if (useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) {
                best(partialResults)?.let(listener::onPartial)
            }

            override fun onResults(results: Bundle) {
                val text = best(results)
                done()
                if (text.isNullOrBlank()) listener.onError("Didn't catch that. Try again.")
                else listener.onResult(text)
            }

            override fun onError(error: Int) {
                done()
                if (useOnDevice && error in MISSING_LANGUAGE) {
                    // The on-device recognizer hasn't got this language; use the default one.
                    SensingLog.add("Speech: on-device recognizer lacks the language (error $error); using the default.")
                    onDeviceFailed = true
                    listen(listener)
                    return
                }
                listener.onError(describe(error))
            }

            override fun onReadyForSpeech(params: Bundle?) {
                level = 0f
            }

            override fun onBeginningOfSpeech() {}

            // Recognizers report roughly -2 dB (quiet) to 10 dB (loud speech).
            override fun onRmsChanged(rmsdB: Float) {
                level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
        done()
    }

    override fun close() = cancel()

    private fun done() {
        recognizer?.destroy()
        recognizer = null
        level = -1f
    }

    private fun best(bundle: Bundle): String? =
        bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Ruby needs the microphone permission to hear you."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone isn't working right now."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Speech recognition needs the internet on this phone right now."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again in a moment."
        else -> "Speech recognition failed (error $error). Try again."
    }

    private companion object {
        /** ERROR_LANGUAGE_NOT_SUPPORTED (12) and ERROR_LANGUAGE_UNAVAILABLE (13), Android 12+. */
        val MISSING_LANGUAGE = setOf(12, 13)
    }
}
