package com.martin.ruby.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.GenerationConfig
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
        // With the dictionary loaded too, so the first checks can give it text.
        val tts = OfflineTts(config = KokoroVoice.kokoroConfig(KokoroModel.dir(context), withLexicon = true))
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

            // Ruby's own word handling: she hands Kokoro sounds, not text (see KokoroPhonemizer).
            val phonemizer = KokoroVoice.loadPhonemizer(KokoroModel.dir(context))
            val tokens = java.io.File(KokoroModel.dir(context), KokoroModel.TOKENS).readLines()
                .mapNotNull { line -> line.split(' ').let { if (it.size == 1 || it[0].isEmpty()) " " else it[0] } }
                .filter { it.codePointCount(0, it.length) == 1 }.map { it.codePointAt(0) }.toSet()

            fun speak(text: String): Pair<String, Float> {
                val sounds = phonemizer.phonemes(KokoroText.normalize(text))
                val unknown = sounds.codePoints().toArray().filter { it !in tokens }
                assertEquals("Sounds Kokoro has no token for, in \"$text\"", emptyList<Int>(), unknown)
                val audio = tts.generateWithConfig("", GenerationConfig(sid = KokoroSpeaker.HEART.sid, phonemeCodepoints = arrayOf(sounds.codePoints().toArray())))
                return sounds to if (audio.sampleRate > 0) audio.samples.size / audio.sampleRate.toFloat() else 0f
            }

            // Every word numbers, dates and units become is in the dictionary itself.
            val writtenOut = buildSet {
                for (n in 0L..120L) { addAll(KokoroText.words(n).split(' ')); addAll(KokoroText.ordinal(n).split(' ')) }
                for (y in listOf(1905, 1999, 2000, 2005, 2026, 2100)) addAll(KokoroText.year(y).split(' '))
                addAll(listOf("thousand", "million", "billion", "minus", "point", "percent", "dollar", "dollars", "cent", "cents", "o'clock"))
                addAll(listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"))
                addAll(listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"))
                addAll(listOf("kilometer", "mile", "miles", "per", "hour", "hours", "meter", "pound", "pounds", "ounce", "gallon", "feet", "foot", "degree", "fahrenheit", "celsius", "plus", "equals", "times", "number", "versus"))
            }
            val notInDictionary = writtenOut.filter { phonemizer.word(it).source != KokoroPhonemizer.Source.DICTIONARY }
            assertEquals("Written-out words missing from the dictionary", emptyList<String>(), notInDictionary)

            // The exact words from the Neptune reply that were spelled out or skipped.
            for ((text, atLeast) in listOf(
                "kilometers" to 0.5f, "Discovered:" to 0.5f, "orbits" to 0.4f, "\"ices\"" to 0.4f, "Neptune's" to 0.5f,
                "noticed" to 0.4f, "suggests" to 0.4f, "Visitors" to 0.5f, "about 1,200 mph" to 1.8f,
                "2,000" to 0.6f, "4.5 billion" to 1.2f, "(2.8 billion miles)" to 1.5f,
            )) {
                val (sounds, seconds) = speak(text)
                log("\"$text\" -> $sounds: %.2f s".format(seconds))
                assertTrue("\"$text\" should be heard, got $seconds s", seconds >= atLeast)
                val spelled = phonemizer.words(KokoroText.normalize(text)).filter { it.source == KokoroPhonemizer.Source.SPELLED }
                assertEquals("\"$text\" shouldn't be spelled out", emptyList<KokoroPhonemizer.Word>(), spelled)
            }

            // The whole reply, sentence by sentence and phrase by phrase, as the voice does it.
            val names = setOf("Urbain", "Le", "Verrier", "Johann", "Galle")
            val chunker = SpeechChunker()
            var seconds = 0f
            var pieces = 0
            val spelledInReply = mutableSetOf<String>()
            for (sentence in chunker.feed(NEPTUNE) + chunker.finish()) {
                for (phrase in KokoroText.phrases(KokoroText.normalize(sentence))) {
                    spelledInReply += phrasesSpelled(phonemizer, phrase)
                    seconds += speak(phrase).second
                    pieces++
                }
            }
            log("Neptune reply: $pieces pieces, %.1f s of speech; spelled out: $spelledInReply".format(seconds))
            assertEquals("Only names may be spelled out", emptySet<String>(), spelledInReply - names)
            assertTrue("The whole reply should be heard, got $seconds s", seconds > 90f)
        } finally {
            tts.release()
        }
    }

    private fun phrasesSpelled(phonemizer: KokoroPhonemizer, phrase: String) =
        phonemizer.words(phrase).filter { it.source == KokoroPhonemizer.Source.SPELLED }.map { it.text }

    private companion object {
        /** The reply that showed the problems: spelled-out words, a word in quotes skipped, a long pause. */
        const val NEPTUNE = "Neptune is the eighth planet from the Sun and the farthest one out, about 4.5 billion kilometers " +
            "(2.8 billion miles) away. Sunlight takes around four hours to reach it. Discovered by math: Astronomers noticed " +
            "Uranus wasn't moving quite as predicted. They suspected something unseen was tugging on it. Urbain Le Verrier " +
            "calculated where that planet should be, and Johann Galle spotted Neptune almost exactly there in 1846. It was the " +
            "first planet found by prediction rather than by chance. An ice giant: Neptune is about four times wider than Earth " +
            "and roughly 17 times as massive. Unlike Jupiter and Saturn, much of it is made of \"ices\" like water, ammonia and " +
            "methane around a rocky core, under a hydrogen and helium atmosphere. Methane gives it its blue color. Recent research " +
            "suggests it's actually a paler blue than the deep blue in old photos, which had been color-enhanced. Wild weather: " +
            "Even though it's so far from the Sun, Neptune has the fastest winds in the solar system, over 2,000 kilometers per " +
            "hour (about 1,200 mph). Voyager 2 saw a huge storm called the Great Dark Spot in 1989, but later images showed it had " +
            "disappeared. A very long year: One trip around the Sun takes about 165 Earth years. Neptune only finished its first " +
            "full orbit since its discovery in 2011. A day there is about 16 hours. Moons and rings: Neptune has 16 known moons. " +
            "The biggest, Triton, orbits backward, against Neptune's spin, so it was probably a captured object from the Kuiper " +
            "Belt. It's one of the coldest places measured in the solar system and has geysers that shoot out nitrogen. Neptune " +
            "also has faint, dark rings. Visitors: Only one spacecraft has ever visited, Voyager 2, which flew past in 1989. " +
            "Neptune is too faint to see without a telescope."
    }
}
