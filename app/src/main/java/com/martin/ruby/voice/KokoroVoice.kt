package com.martin.ruby.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.SystemClock
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ruby's voice made on the phone by Kokoro (Phase 8 step 9), so it works offline. Like
 * [AndroidTtsVoice], other audio is turned down while she talks.
 *
 * Two threads, so she talks without gaps: the *maker* turns sentences into audio while the
 * *player* plays the audio already made. On the phone Kokoro makes speech only a little
 * faster than it's spoken (about 0.9 seconds per second of speech, a whole sentence at a
 * time), so with one thread doing both there was a silence after every sentence while the
 * next one was made. Now the next sentence is made while the current one plays.
 *
 * [stop] pauses and empties the speaker on the spot, and the player writes in small slices
 * and checks for a stop between them, so the sound ends at once rather than after the
 * sentence that was playing.
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

    /** Text to say, tagged with the [epoch] it was said in. */
    private class Line(val epoch: Int, val text: String)

    /** Audio made for one line; [done] marks the end of a line (then [samples] is empty). */
    private class Chunk(val epoch: Int, val samples: FloatArray, val sampleRate: Int, val done: Boolean = false)

    private val lines = LinkedBlockingQueue<Line>()

    /** Made but not yet played. Bounded, so the maker runs at most a few sentences ahead. */
    private val chunks = LinkedBlockingQueue<Chunk>(MAX_CHUNKS_AHEAD)

    /** Bumped by [stop]: anything said, made or queued before it is dropped. */
    private val epoch = AtomicInteger(0)

    /** Lines said in this epoch and not yet played to the end. */
    private val pending = AtomicInteger(0)

    /** Guards [pending] and [speaking] together, so "finished" can't cross a new [say]. */
    private val state = Any()

    @Volatile private var speed = 1.0f
    @Volatile private var closed = false

    /** The speaker. Written by the player thread; [stop] pauses it from any thread. */
    @Volatile private var track: AudioTrack? = null

    private val maker = Thread(::makeLoop, "kokoro-maker").apply { isDaemon = true }
    private val player = Thread(::playLoop, "kokoro-player").apply { isDaemon = true }

    init {
        if (!KokoroModel.isInstalled(context)) _problem.value = "The Kokoro voice isn't downloaded yet."
        maker.start()
        player.start()
    }

    override fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || closed || _problem.value != null) return
        synchronized(state) {
            // Numbers, units and symbols become words first; then long sentences go in phrases,
            // each made quickly, so a long sentence after a short one doesn't leave a silence
            // while it's made (see KokoroText).
            val starting = !_speaking.value
            val phrases = KokoroText.phrases(KokoroText.normalize(clean))
            // At the start of a stretch of speech, short first pieces, so she starts talking soon.
            for (phrase in if (starting) KokoroText.leadIn(phrases) else phrases) {
                pending.incrementAndGet()
                lines.add(Line(epoch.get(), phrase))
            }
            if (starting) {
                _speaking.value = true
                audio.requestAudioFocus(focus)
                // Timing the first sound of this stretch of speech (see firstSound()).
                sayStartedAt = SystemClock.elapsedRealtime()
                awaitingFirstSound = true
            }
        }
    }

    override fun stop() {
        synchronized(state) {
            epoch.incrementAndGet()
            lines.clear()
            chunks.clear()
            pending.set(0)
            // Right here, not on the player thread (which may be busy): silence at once.
            track?.let { out ->
                runCatching {
                    out.pause()
                    out.flush()
                }
            }
            finishedLocked()
        }
    }

    override fun noteReplyArrived(atMillis: Long) {
        replyArrivedAt = atMillis
    }

    override fun setStyle(style: VoiceStyle) {
        speed = style.speed // Kokoro has no pitch setting
    }

    override fun close() {
        closed = true
        stop()
        maker.interrupt()
        player.interrupt()
    }

    // The maker: text in, audio out

    private var tts: OfflineTts? = null // maker thread only

    /** Turns words into Kokoro's sounds, from its dictionary. Maker thread only. */
    private var phonemizer: KokoroPhonemizer? = null

    private fun makeLoop() {
        try {
            while (!closed) {
                val line = lines.take()
                if (line.epoch != epoch.get()) continue
                try {
                    make(line)
                } catch (e: Exception) {
                    SensingLog.add("Kokoro voice failed: ${e.message ?: e.javaClass.simpleName}")
                    _problem.value = "The Kokoro voice stopped working: ${e.message ?: e.javaClass.simpleName}"
                    stop()
                }
            }
        } catch (e: InterruptedException) {
            // closed
        } finally {
            tts?.release()
            tts = null
        }
    }

    private fun make(line: Line) {
        val engine = engine() ?: return
        val rate = engine.sampleRate()
        // Ruby works out the sounds herself and hands Kokoro those, not text: given text, the
        // library skips every word its dictionary lacks (plurals, past tenses, possessives) and
        // any word with a quote or bracket stuck to it.
        val sounds = phonemizer!!.phonemes(line.text)
        if (sounds.isNotBlank()) {
            val config = GenerationConfig(sid = speaker.sid, speed = speed, phonemeCodepoints = arrayOf(sounds.codePoints().toArray()))
            engine.generateWithConfigAndCallback("", config, SampleSink { samples ->
                hand(Chunk(line.epoch, samples, rate)) // false (stop making) once stopped
            })
        }
        hand(Chunk(line.epoch, FloatArray(0), rate, done = true))
    }

    /** Passes audio to the player, waiting while it's far enough ahead. False if stopped meanwhile. */
    private fun hand(chunk: Chunk): Boolean {
        while (chunk.epoch == epoch.get() && !closed) {
            if (chunks.offer(chunk, 50, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    /** Loads the model the first time (a second or two). */
    private fun engine(): OfflineTts? {
        tts?.let { return it }
        if (!KokoroModel.isInstalled(modelDir)) {
            _problem.value = "The Kokoro voice isn't downloaded yet."
            stop()
            return null
        }
        // Read the dictionary (about 0.7 s on the phone) while Kokoro loads (about 0.85 s).
        var loaded: KokoroPhonemizer? = null
        var failed: Throwable? = null
        val reader = Thread({
            try {
                loaded = loadPhonemizer(modelDir)
            } catch (e: Throwable) {
                failed = e
            }
        }, "kokoro-dictionary").apply { start() }
        val engine = OfflineTts(config = kokoroConfig(modelDir))
        reader.join()
        failed?.let { engine.release(); throw it }
        phonemizer = loaded
        return engine.also { tts = it }
    }

    // The player: audio out of the speaker

    private var playingEpoch = -1 // player thread only
    private var framesWritten = 0L // player thread only: written in playingEpoch since the last flush

    private fun playLoop() {
        try {
            while (!closed) {
                // How much audio is still queued in the speaker, to tell a real silence from a wait
                // that the queued audio covered.
                val waitStarted = SystemClock.elapsedRealtime()
                val midSpeech = pending.get() > 0 && playingEpoch == epoch.get() && framesWritten > 0
                val queuedMs = if (midSpeech) (framesWritten - playedFrames()).coerceAtLeast(0) * 1000 / SAMPLE_RATE else 0
                val chunk = chunks.take()
                if (midSpeech && chunk.epoch == playingEpoch) {
                    val silentMs = SystemClock.elapsedRealtime() - waitStarted - queuedMs
                    if (silentMs > AUDIBLE_GAP_MS) {
                        gaps++
                        gapMillis += silentMs
                        longestGapMillis = maxOf(longestGapMillis, silentMs)
                    }
                }
                if (chunk.epoch != epoch.get()) continue
                if (!chunk.done) {
                    play(chunk)
                    continue
                }
                // A line has been played. If it was the last one, let the sound finish, then stop.
                if (pending.decrementAndGet() <= 0) {
                    waitUntilPlayed(chunk.epoch)
                    synchronized(state) {
                        if (chunk.epoch == epoch.get() && pending.get() <= 0 && chunks.isEmpty() && lines.isEmpty()) {
                            track?.pause() // an idle speaker left running wastes power
                            finishedLocked()
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            // closed
        } finally {
            track?.release()
            track = null
        }
    }

    private fun play(chunk: Chunk) {
        val out = track ?: makeTrack(chunk.sampleRate).also { track = it }
        if (playingEpoch != chunk.epoch) {
            // Anything left from before a stop must not be heard.
            out.pause()
            out.flush()
            framesWritten = 0
            playingEpoch = chunk.epoch
        }
        if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
        if (awaitingFirstSound) {
            awaitingFirstSound = false
            firstSound(SystemClock.elapsedRealtime())
        }
        var offset = 0
        while (offset < chunk.samples.size) {
            if (chunk.epoch != epoch.get()) return // stopped: write no more
            val length = minOf(SLICE, chunk.samples.size - offset)
            val written = out.write(chunk.samples, offset, length, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) return
            offset += written
            framesWritten += written
        }
    }

    private fun makeTrack(sampleRate: Int): AudioTrack {
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
    }

    /** Waits for the last audio to come out of the speaker, unless more arrives or she's stopped. */
    private fun waitUntilPlayed(forEpoch: Int) {
        val out = track ?: return
        var lastPosition = -1L
        var stuck = 0
        while (forEpoch == epoch.get() && chunks.isEmpty() && !closed) {
            val position = out.playbackHeadPosition.toLong() and 0xffffffffL
            if (position >= framesWritten) break
            stuck = if (position == lastPosition) stuck + 1 else 0
            if (stuck > 50) break // not moving for a second: give up waiting
            lastPosition = position
            Thread.sleep(20)
        }
    }

    // Time to first sound

    @Volatile private var replyArrivedAt = 0L
    @Volatile private var sayStartedAt = 0L
    @Volatile private var awaitingFirstSound = false

    /** For tests: the last stretch of speech's time from the first say() to the first sound. */
    @Volatile internal var lastFirstSoundMillis = -1L
        private set

    /**
     * Logs how long the first sound took: from the reply starting to arrive (if this is a
     * reply), split into waiting for its first sentence and Kokoro making it.
     */
    private fun firstSound(now: Long) {
        val making = now - sayStartedAt
        lastFirstSoundMillis = making
        val reply = replyArrivedAt
        val message = if (reply in 1..sayStartedAt && sayStartedAt - reply < 60_000) {
            "Voice: first sound ${now - reply} ms after the reply started arriving " +
                "(${sayStartedAt - reply} ms for its first sentence, $making ms making it)."
        } else {
            "Voice: first sound $making ms after it was asked to speak."
        }
        replyArrivedAt = 0
        Log.i("RubyVoice", message)
        SensingLog.add(message)
    }

    /** For tests: silences mid-speech, while the next piece was still being made, and their total length. */
    @Volatile internal var gaps = 0
        private set
    @Volatile internal var gapMillis = 0L
        private set
    @Volatile internal var longestGapMillis = 0L
        private set

    /** For tests: how much audio (in samples) has come out of the speaker since the last flush. */
    internal fun playedFrames(): Long = (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL

    /** Call with [state] held. */
    private fun finishedLocked() {
        if (_speaking.value) {
            _speaking.value = false
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /**
     * Receives Kokoro's audio as it's made; [onSamples] returns false to stop early.
     *
     * It has to be a real class. sherpa-onnx's native code looks the callback up by the exact
     * method `invoke(float[]): Integer`. A Kotlin 2 lambda doesn't have that method (it becomes
     * a generic object whose `invoke` takes and returns `Object`), so the lookup failed inside
     * native code and Android killed the app on Kokoro's first sentence. A class implementing
     * the function type gets exactly that method.
     */
    class SampleSink(private val onSamples: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (onSamples(samples)) 1 else 0
    }

    companion object {
        /**
         * Measured on the phone (Snapdragon, 8 cores): 4 threads is fastest, about 0.88 s to
         * make each second of speech; 6 is the same, 8 and 1 are slower.
         */
        const val THREADS = 4

        /** Kokoro's output rate (fixed for this model). */
        private const val SAMPLE_RATE = 24_000

        /** Silences shorter than this aren't noticed. */
        private const val AUDIBLE_GAP_MS = 120

        /** The player writes this much (50 ms) at a time, so a stop is heard within a slice. */
        private const val SLICE = 1_200

        /** How much made audio (in chunks, roughly sentences) may wait to be played. */
        private const val MAX_CHUNKS_AHEAD = 8

        /** Ruby's own reader of the model's US English dictionary (see [KokoroPhonemizer]). */
        fun loadPhonemizer(dir: File) = KokoroPhonemizer(
            File(dir, KokoroModel.LEXICON_US).bufferedReader().useLines { KokoroPhonemizer.load(it) }
        )

        /**
         * How the model in [dir] is loaded; the device tests use the same. Ruby hands Kokoro
         * sounds, not text, so it doesn't load its own copy of the dictionary unless
         * [withLexicon] (tests that give it text).
         */
        fun kokoroConfig(dir: File, withLexicon: Boolean = false) = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(dir, KokoroModel.MODEL).path,
                    voices = File(dir, KokoroModel.VOICES).path,
                    tokens = File(dir, KokoroModel.TOKENS).path,
                    lexicon = if (withLexicon) File(dir, KokoroModel.LEXICON_US).path else "",
                ),
                numThreads = THREADS,
            ),
        )
    }
}
