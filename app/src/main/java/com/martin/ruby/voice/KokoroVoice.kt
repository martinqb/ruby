package com.martin.ruby.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ruby's voice made on the phone by Kokoro (Phase 8 step 9), so it works offline. Sentences
 * are made one at a time on a background thread and played as the audio comes out, so she
 * starts talking before a long reply is finished. Like [AndroidTtsVoice], other audio is
 * turned down while she talks.
 *
 * Needs [KokoroModel] installed; [problem] says so otherwise.
 */
class KokoroVoice(context: Context, private val speaker: KokoroSpeaker) : RubyVoice {

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()

    private val modelDir: File = KokoroModel.dir(context.applicationContext)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    /** Everything that touches the model or the speaker runs on this one thread. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kokoro-voice").apply { isDaemon = true } }
    private val queue = LinkedBlockingQueue<String>()

    /** Bumped by [stop]: audio being made for an older value is dropped. */
    private val epoch = AtomicInteger(0)
    @Volatile private var speed = 1.0f
    @Volatile private var closed = false

    // Only used on the worker thread.
    private var tts: OfflineTts? = null
    /** The dictionary's words, to spot ones Kokoro would drop (see [KokoroText]). */
    private var lexicon: Set<String> = emptySet()
    private var track: AudioTrack? = null
    private var framesWritten = 0L

    init {
        if (!KokoroModel.isInstalled(context)) _problem.value = "The Kokoro voice isn't downloaded yet."
    }

    override fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || closed || _problem.value != null) return
        queue.add(clean)
        if (!_speaking.value) {
            _speaking.value = true
            audio.requestAudioFocus(focus)
        }
        val myEpoch = epoch.get()
        worker.execute { drain(myEpoch) }
    }

    override fun stop() {
        epoch.incrementAndGet()
        queue.clear()
        worker.execute {
            track?.run {
                pause()
                flush()
            }
            framesWritten = 0
        }
        finished()
    }

    override fun setStyle(style: VoiceStyle) {
        speed = style.speed // Kokoro has no pitch setting
    }

    override fun close() {
        closed = true
        stop()
        worker.execute {
            track?.release()
            track = null
            tts?.release()
            tts = null
        }
        worker.shutdown()
    }

    /** Speaks what's queued, unless [stop] was called since it was asked for. */
    private fun drain(myEpoch: Int) {
        try {
            while (epoch.get() == myEpoch) {
                val text = queue.poll()
                if (text == null) {
                    // Nothing more yet: let the audio play out, but carry on at once if the
                    // next sentence of the reply arrives meanwhile.
                    waitUntilPlayed()
                    if (queue.isEmpty()) {
                        if (epoch.get() == myEpoch) finished()
                        return
                    }
                    continue
                }
                val engine = engine() ?: return
                val out = track(engine.sampleRate())
                if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                // Numbers become words and unknown words are spelled out: Kokoro would drop them.
                val spoken = KokoroText.prepare(text) { it in lexicon }
                engine.generateWithCallback(spoken, speaker.sid, speed) { samples ->
                    if (epoch.get() != myEpoch) return@generateWithCallback 0 // stopped: end early
                    out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    framesWritten += samples.size
                    1
                }
            }
        } catch (e: Exception) {
            SensingLog.add("Kokoro voice failed: ${e.message ?: e.javaClass.simpleName}")
            _problem.value = "The Kokoro voice stopped working: ${e.message ?: e.javaClass.simpleName}"
            queue.clear()
            finished()
        }
    }

    /** Loads the model the first time (a second or two). */
    private fun engine(): OfflineTts? {
        tts?.let { return it }
        if (!KokoroModel.isInstalled(modelDir)) {
            _problem.value = "The Kokoro voice isn't downloaded yet."
            finished()
            return null
        }
        lexicon = loadLexicon(modelDir)
        return OfflineTts(config = kokoroConfig(modelDir)).also { tts = it }
    }

    private fun track(sampleRate: Int): AudioTrack {
        track?.let { return it }
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate * 4 / 2)) // about half a second
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { track = it; framesWritten = 0 }
    }

    /** Waits for the last of the audio to come out of the speaker, or for more to say. */
    private fun waitUntilPlayed() {
        val out = track ?: return
        val myEpoch = epoch.get()
        var lastPosition = -1L
        var stuck = 0
        while (epoch.get() == myEpoch && queue.isEmpty()) {
            val position = out.playbackHeadPosition.toLong() and 0xffffffffL
            if (position >= framesWritten) break
            stuck = if (position == lastPosition) stuck + 1 else 0
            if (stuck > 50) break // not moving for a second: give up waiting
            lastPosition = position
            Thread.sleep(20)
        }
    }

    private fun finished() {
        if (_speaking.value) {
            _speaking.value = false
            audio.abandonAudioFocusRequest(focus)
        }
    }

    companion object {
        /** Kokoro is quicker with a few threads; sherpa-onnx's own default for it is 4. */
        const val THREADS = 4

        /** The words in the model's US English dictionary (the first field of each line). */
        fun loadLexicon(dir: File): Set<String> = HashSet<String>(250_000).also { words ->
            File(dir, KokoroModel.LEXICON_US).forEachLine { line ->
                val end = line.indexOf(' ')
                if (end > 0) words += line.substring(0, end)
            }
        }

        /** How the model in [dir] is loaded; the device test uses the same. */
        fun kokoroConfig(dir: File) = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, KokoroModel.MODEL).path,
                    voices = File(dir, KokoroModel.VOICES).path,
                    tokens = File(dir, KokoroModel.TOKENS).path,
                    // The lexicon decides the language: American English.
                    lexicon = File(dir, KokoroModel.LEXICON_US).path,
                ),
                numThreads = THREADS,
            ),
        )
    }
}
