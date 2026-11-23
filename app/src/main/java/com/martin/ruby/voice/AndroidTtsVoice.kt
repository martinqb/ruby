package com.martin.ruby.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Ruby's voice using Android's built-in text-to-speech (whatever engine the phone has set,
 * e.g. Samsung's or Google's). Speech is made on the phone by that engine.
 *
 * While she talks, other audio (music, navigation) is turned down rather than paused, and
 * comes back up when she's done.
 */
class AndroidTtsVoice(context: Context) : RubyVoice {

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    /**
     * Utterances queued or playing. The engine calls back on its own thread, and can report
     * on ones [stop] already dropped, so only ids still in here count.
     */
    private val outstanding = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicLong(0)

    /** Text said before the engine finished starting up. */
    private val waiting = mutableListOf<String>()
    private var ready = false
    private var style = VoiceStyle()

    private val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            applyStyle()
            waiting.forEach(::enqueue)
            waiting.clear()
        } else {
            val message = "This phone's text-to-speech engine didn't start. Check Settings > " +
                "General management > Text-to-speech."
            _problem.value = message
            SensingLog.add("Voice: $message")
            waiting.clear()
        }
    }

    init {
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finished(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finished(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
        })
    }

    override fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _problem.value != null) return
        if (ready) enqueue(clean) else waiting += clean
        if (!_speaking.value) {
            _speaking.value = true
            audio.requestAudioFocus(focus)
        }
    }

    override fun stop() {
        waiting.clear()
        outstanding.clear()
        tts.stop()
        release()
    }

    override fun setStyle(style: VoiceStyle) {
        this.style = style
        if (ready) applyStyle()
    }

    override fun close() {
        stop()
        tts.shutdown()
    }

    private fun enqueue(text: String) {
        // Engines have a length limit (about 4,000 characters); split anything longer.
        text.chunked(TextToSpeech.getMaxSpeechInputLength() - 1).forEach { part ->
            val id = "ruby-${nextId.incrementAndGet()}"
            outstanding += id
            if (tts.speak(part, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) finished(id)
        }
    }

    private fun finished(id: String) {
        if (outstanding.remove(id) && outstanding.isEmpty() && waiting.isEmpty()) release()
    }

    private fun release() {
        if (_speaking.value) {
            _speaking.value = false
            audio.abandonAudioFocusRequest(focus)
        }
    }

    private fun applyStyle() {
        tts.setSpeechRate(style.speed)
        tts.setPitch(style.pitch)
    }
}
