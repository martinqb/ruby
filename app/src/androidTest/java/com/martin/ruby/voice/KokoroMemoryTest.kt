package com.martin.ruby.voice

import android.os.Debug
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
 * What the voice costs to load: Kokoro with and without its own copy of the dictionary, and
 * Ruby's dictionary reader (KokoroPhonemizer). Read only: changes nothing, so it's safe on a
 * real phone (run just this class). Results go to the log ("KokoroMemory").
 */
@RunWith(AndroidJUnit4::class)
class KokoroMemoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(message: String) = Log.i("KokoroMemory", message)

    private fun nativeMb() = Debug.getNativeHeapAllocatedSize() / 1_048_576

    private fun javaMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1_048_576 }

    @Test
    fun cost() {
        assumeTrue("Needs the Kokoro voice downloaded", KokoroModel.isInstalled(context))
        val dir = KokoroModel.dir(context)

        System.gc()
        val java = javaMb()
        var started = SystemClock.elapsedRealtime()
        val phonemizer = KokoroVoice.loadPhonemizer(dir)
        System.gc()
        log("Ruby's dictionary reader: ${SystemClock.elapsedRealtime() - started} ms, Java memory +${javaMb() - java} MB")

        for (withLexicon in listOf(true, false, true, false)) {
            System.gc()
            val before = nativeMb()
            started = SystemClock.elapsedRealtime()
            val tts = OfflineTts(config = KokoroVoice.kokoroConfig(dir, withLexicon = withLexicon))
            val loadMs = SystemClock.elapsedRealtime() - started
            val after = nativeMb()
            val sounds = phonemizer.phonemes(KokoroText.normalize("They noticed the orbits, 4.5 billion kilometers away."))
            started = SystemClock.elapsedRealtime()
            val audio = tts.generateWithConfig("", GenerationConfig(sid = KokoroSpeaker.HEART.sid, phonemeCodepoints = arrayOf(sounds.codePoints().toArray())))
            log(
                (if (withLexicon) "Kokoro WITH its dictionary:    " else "Kokoro without its dictionary: ") +
                    "load $loadMs ms, native memory +${after - before} MB; " +
                    "test sentence %.2f s of speech in ${SystemClock.elapsedRealtime() - started} ms".format(audio.samples.size / 24_000f)
            )
            tts.release()
        }
    }
}
