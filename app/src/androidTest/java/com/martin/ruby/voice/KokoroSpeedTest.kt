package com.martin.ruby.voice

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how fast Kokoro speaks on this device, with different numbers of threads. Read
 * only: it uses the installed model and changes nothing, so it's safe to run on a real phone
 * (run just this class). Results go to the log under "KokoroSpeed".
 */
@RunWith(AndroidJUnit4::class)
class KokoroSpeedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sentences = listOf(
        "Good morning.",
        "Your tyre check is at nine, and it's thirty six degrees outside, so take a coat.",
        "I've set a reminder for five thirty p m to call Mom, and another for when you get to the gym tomorrow.",
    )

    @Test
    fun speedByThreads() {
        assumeTrue("Needs the Kokoro voice downloaded", KokoroModel.isInstalled(context))
        val dir = KokoroModel.dir(context)
        for (threads in listOf(1, 2, 4, 6, 8)) {
            val config = KokoroVoice.kokoroConfig(dir).let { c -> c.copy(model = c.model.copy(numThreads = threads)) }
            val tts = OfflineTts(config = config)
            try {
                tts.generate("Warm up.", KokoroSpeaker.HEART.sid, 1.0f) // first run is slower
                var audioSeconds = 0f
                var tookMs = 0L
                var firstAudioMs = 0L
                for (sentence in sentences) {
                    val started = SystemClock.elapsedRealtime()
                    var first = -1L
                    var samples = 0
                    tts.generateWithCallback(sentence, KokoroSpeaker.HEART.sid, 1.0f, KokoroVoice.SampleSink { chunk ->
                        if (first < 0) first = SystemClock.elapsedRealtime() - started
                        samples += chunk.size
                        true
                    })
                    tookMs += SystemClock.elapsedRealtime() - started
                    firstAudioMs = maxOf(firstAudioMs, first)
                    audioSeconds += samples / tts.sampleRate().toFloat()
                }
                Log.i(
                    "KokoroSpeed",
                    "threads=$threads: %.1f s of audio in %d ms = %.2fx real time; slowest first audio %d ms"
                        .format(audioSeconds, tookMs, tookMs / 1000f / audioSeconds, firstAudioMs),
                )
            } finally {
                tts.release()
            }
        }
    }
}
