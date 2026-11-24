package com.martin.ruby.voice

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long Kokoro takes to make pieces of different lengths, warm, as the voice makes them:
 * the first piece's making time is most of the wait before Ruby's first sound. Read only:
 * changes nothing, so it's safe on a real phone (run just this class). Log tag "KokoroFirst".
 */
@RunWith(AndroidJUnit4::class)
class KokoroFirstPieceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val pieces = listOf(
        "Sure.",
        "Neptune is far.",
        "Neptune is the eighth planet,",
        "Neptune is the eighth planet from the Sun,",
        "Neptune is the eighth planet from the Sun and the farthest one out,",
    )

    @Test
    fun makingTimeByLength() {
        assumeTrue("Needs the Kokoro voice downloaded", KokoroModel.isInstalled(context))
        val dir = KokoroModel.dir(context)
        val phonemizer = KokoroVoice.loadPhonemizer(dir)
        val tts = OfflineTts(config = KokoroVoice.kokoroConfig(dir))
        try {
            fun make(text: String): Pair<Long, Float> {
                val sounds = phonemizer.phonemes(KokoroText.normalize(text)).codePoints().toArray()
                val started = SystemClock.elapsedRealtime()
                val audio = tts.generateWithConfig("", GenerationConfig(sid = KokoroSpeaker.NICOLE.sid, phonemeCodepoints = arrayOf(sounds)))
                return (SystemClock.elapsedRealtime() - started) to audio.samples.size / audio.sampleRate.toFloat()
            }
            make("Warm up.")
            for (piece in pieces) {
                val runs = (1..3).map { make(piece) }
                val ms = runs.map { it.first }.sorted()[1] // the middle of three
                val seconds = runs[0].second
                Log.i("KokoroFirst", "%2d chars, %.2f s of speech: made in %d ms (%.2fx)".format(piece.length, seconds, ms, ms / 1000f / seconds))
            }
        } finally {
            tts.release()
        }
    }
}
