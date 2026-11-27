package com.martin.ruby.listening

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.martin.ruby.log.SensingLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Listens to the mic on the phone with Vosk, for the listening modes:
 * - [MicUse.WAKE]: only the wake phrase (a grammar of just that phrase), calling [Listener.onWake];
 * - [MicUse.BARGE_IN]: any words, while Ruby talks, calling [Listener.onHeard] with what it hears.
 *
 * Audio comes through Android's echo cancellation (the voice-call mic, plus the echo canceller
 * where the phone has one), so what Ruby and the radio play is mostly taken out. Calls and
 * callbacks are on the main thread. [stop] returns once the mic is released, so Android's
 * speech recognizer can have it straight after.
 */
class VoskSpotter(private val model: Model) {

    interface Listener {
        fun onWake()
        fun onHeard(text: String)
        fun onProblem(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: Thread? = null
    @Volatile private var running = false
    /** Counts starts, so a callback from a run that has since stopped is dropped. */
    @Volatile private var generation = 0

    var use: MicUse = MicUse.NONE
        private set
    var phrase: String = ""
        private set

    @SuppressLint("MissingPermission") // Listening checks RECORD_AUDIO before starting.
    fun start(use: MicUse, phrase: String, listener: Listener) {
        stop()
        if (use == MicUse.NONE) return
        this.use = use
        this.phrase = phrase
        running = true
        val run = ++generation
        thread = Thread({ run(run, use, phrase, listener) }, "ruby-listening").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.let { t ->
            t.interrupt()
            t.join(STOP_WAIT_MS)
        }
        thread = null
        use = MicUse.NONE
    }

    @SuppressLint("MissingPermission")
    private fun run(run: Int, use: MicUse, phrase: String, listener: Listener) {
        fun post(block: () -> Unit) = main.post { if (running && generation == run) block() }
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // the phone's echo-cancelled mic
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME * 2 * 4),
            )
        } catch (e: Exception) {
            post { listener.onProblem("The microphone couldn't be opened.") }
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            post { listener.onProblem("The microphone couldn't be opened.") }
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val recognizer = try {
            if (use == MicUse.WAKE) Recognizer(model, RATE.toFloat(), WakePhrase.grammar(phrase)) else Recognizer(model, RATE.toFloat())
        } catch (e: Exception) {
            record.release()
            post { listener.onProblem("The listening model couldn't start.") }
            return
        }
        SensingLog.add(
            "Listening: " + (if (use == MicUse.WAKE) "for \"$phrase\"" else "for you talking over Ruby") +
                " (echo canceller ${if (aec != null) "on" else "not on this phone"})."
        )
        val buffer = ShortArray(FRAME)
        try {
            record.startRecording()
            var lastPartial = ""
            while (running && generation == run && !Thread.currentThread().isInterrupted) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                val final = recognizer.acceptWaveForm(buffer, n)
                val text = if (final) text(recognizer.result, "text") else text(recognizer.partialResult, "partial")
                if (text.isEmpty() || (!final && text == lastPartial)) continue
                lastPartial = if (final) "" else text
                when (use) {
                    MicUse.WAKE -> if (WakePhrase.heard(phrase, text)) {
                        recognizer.reset()
                        lastPartial = ""
                        post { listener.onWake() }
                    }
                    MicUse.BARGE_IN -> post { listener.onHeard(text) }
                    MicUse.NONE -> Unit
                }
            }
        } catch (e: Exception) {
            if (running) post { listener.onProblem("Listening stopped: ${e.message ?: "the microphone failed"}.") }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            aec?.release()
            ns?.release()
            recognizer.close()
        }
    }

    private fun text(json: String, key: String): String = try {
        JSONObject(json).optString(key).trim()
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val RATE = 16_000
        /** 100 ms of audio at a time. */
        const val FRAME = 1_600
        const val STOP_WAIT_MS = 1_000L
    }
}
