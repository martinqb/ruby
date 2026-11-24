package com.martin.ruby.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * The real Kokoro model on an (emulated) phone: installed through the app's own code (checked
 * against GitHub's SHA-256), unpacked, loaded, and made to speak with both of Ruby's voices.
 * Emulator only: it replaces the installed model.
 */
@RunWith(AndroidJUnit4::class)
class KokoroDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun isEmulator() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")

    private fun log(message: String) = Log.i("KokoroDeviceTest", message)

    @Test
    fun downloadsInstallsAndSpeaksWithBothVoices() = runBlocking {
        assumeTrue("Replaces the installed voice: runs on an emulator only", isEmulator())
        if (!KokoroModel.isInstalled(context)) {
            var last = 0f
            val started = SystemClock.elapsedRealtime()
            // The emulator can't download through this PC's antivirus, so the test copies the
            // package (checked on the PC) into the app's cache first; it's checked again here.
            val local = java.io.File(context.cacheDir, "kokoro.tar.bz2").takeIf { it.exists() }
            KokoroModel.install(context, archive = local) { last = it }
            log("Downloaded and installed in ${(SystemClock.elapsedRealtime() - started) / 1000} s")
            assertEquals(1f, last)
        }
        assertTrue(KokoroModel.isInstalled(context))
        val files = KokoroModel.dir(context).listFiles()!!.associate { it.name to it.length() }
        log("Installed: $files (${files.values.sum() / 1_048_576} MB)")
        assertEquals(KokoroModel.KEEP, files.keys)

        val loading = SystemClock.elapsedRealtime()
        val tts = OfflineTts(config = KokoroVoice.kokoroConfig(KokoroModel.dir(context)))
        log("Model loaded in ${SystemClock.elapsedRealtime() - loading} ms; ${tts.numSpeakers()} speakers")
        try {
            assertEquals(24_000, tts.sampleRate())
            assertTrue(tts.numSpeakers() > KokoroSpeaker.entries.maxOf { it.sid })

            val sentence = "Good morning. Your tyre check is at nine, and it's thirty six degrees outside."
            val bySpeaker = KokoroSpeaker.entries.associateWith { speaker ->
                val started = SystemClock.elapsedRealtime()
                val audio = tts.generate(sentence, sid = speaker.sid, speed = 1.0f)
                val took = SystemClock.elapsedRealtime() - started
                val seconds = audio.samples.size / audio.sampleRate.toFloat()
                val peak = audio.samples.maxOf { abs(it) }
                log("${speaker.label}: %.1f s of audio in $took ms (%.2f× real time), peak %.2f".format(seconds, took / 1000f / seconds, peak))
                assertTrue("${speaker.label} should take a few seconds to say it", seconds in 2f..15f)
                assertTrue("${speaker.label} shouldn't be silent", peak > 0.05f)
                audio.samples
            }
            assertTrue("Heart and Nicole should sound different", !bySpeaker[KokoroSpeaker.HEART].contentEquals(bySpeaker[KokoroSpeaker.NICOLE]))

            // Streaming, the way the app speaks: audio arrives through the callback. This crashed
            // the app (the native code couldn't find the callback's method) until KokoroVoice.SampleSink.
            var streamed = 0
            var chunks = 0
            tts.generateWithCallback(sentence, KokoroSpeaker.HEART.sid, 1.0f, KokoroVoice.SampleSink { samples ->
                streamed += samples.size
                chunks++
                true
            })
            log("Streamed $streamed samples in $chunks chunks")
            assertTrue("Audio should come through the callback", streamed > tts.sampleRate()) // over a second

            // Stop: returning false ends it early, as Stop talking does.
            var afterStop = 0
            tts.generateWithCallback("$sentence $sentence $sentence", KokoroSpeaker.HEART.sid, 1.0f, KokoroVoice.SampleSink { samples ->
                afterStop += samples.size
                false
            })
            log("Stopped after $afterStop samples")
            assertTrue("Stopping should end it well before the whole text", afterStop < streamed)

            // Kokoro drops digits and words it doesn't know; KokoroText writes them out first.
            val lexicon = KokoroVoice.loadLexicon(KokoroModel.dir(context))
            log("Dictionary: ${lexicon.size} words")
            val numberWords = buildSet {
                for (n in 0L..120L) { addAll(KokoroText.words(n).split(' ')); addAll(KokoroText.ordinal(n).split(' ')) }
                for (y in listOf(1905, 1999, 2000, 2005, 2026, 2100)) addAll(KokoroText.year(y).split(' '))
                addAll(listOf("thousand", "million", "billion", "minus", "point", "percent", "dollar", "dollars", "cent", "cents", "o'clock"))
                addAll(listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"))
                addAll(listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"))
            }
            assertEquals("Every word numbers become must be in the dictionary", emptySet<String>(), numberWords - lexicon)

            for ((text, atLeast) in listOf(
                "36 psi" to 1.0f, "5:00 PM" to 0.8f, "2026" to 0.9f, "3.5" to 0.8f, "Nov 24" to 1.0f,
                "Zyxquilbrant" to 1.5f, "your GPS" to 0.8f, "Hello how are you" to 0.8f,
            )) {
                val spoken = KokoroText.prepare(text) { it in lexicon }
                val audio = tts.generate(spoken, sid = KokoroSpeaker.HEART.sid, speed = 1.0f)
                val seconds = if (audio.sampleRate > 0) audio.samples.size / audio.sampleRate.toFloat() else 0f
                log("\"$text\" -> \"$spoken\": %.2f s".format(seconds))
                assertTrue("\"$text\" (said as \"$spoken\") should be heard, got $seconds s", seconds >= atLeast)
            }
        } finally {
            tts.release()
        }
    }
}
