package com.martin.ruby.voice

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Kokoro voice playing for real: a reply's sentences play without gaps, and Stop silences
 * it at once. Plays sound, so it runs on the emulator only. Needs the model installed
 * (KokoroDeviceTest installs it).
 */
@RunWith(AndroidJUnit4::class)
class KokoroVoiceDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var voice: KokoroVoice? = null

    private fun isEmulator() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")

    private fun log(message: String) = Log.i("KokoroVoiceTest", message)

    private fun ready(): KokoroVoice {
        assumeTrue("Plays sound: runs on an emulator only", isEmulator())
        assumeTrue("Needs the Kokoro voice installed", KokoroModel.isInstalled(context))
        return KokoroVoice(context, KokoroSpeaker.HEART).also { voice = it }
    }

    @After
    fun close() {
        voice?.close()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun aRepliesSentences_playWithoutGaps() {
        val v = ready()
        // Like a streamed reply: sentences arrive one after another.
        val sentences = listOf(
            "Good morning.",
            "Your tyre check is at nine, and it's thirty six degrees outside, so take a coat.",
            "I've set a reminder for five thirty to call Mom.",
            "And don't forget the dentist on Friday afternoon.",
        )
        val started = SystemClock.elapsedRealtime()
        sentences.forEach(v::say)
        assertTrue(v.speaking.value)
        assertTrue("She should start talking", waitUntil(15_000) { v.playedFrames() > 0 })
        val firstSound = SystemClock.elapsedRealtime() - started
        assertTrue("She should finish", waitUntil(60_000) { !v.speaking.value })
        val seconds = v.playedFrames() / 24_000f
        log(
            "First sound after $firstSound ms; played %.1f s; ${v.gaps} gaps, ${v.gapMillis} ms of silence, longest ${v.longestGapMillis} ms"
                .format(seconds)
        )
        assertTrue("All four sentences should be heard", seconds > 8f)
        // Before the maker and player were split, almost every sentence was followed by a silence of
        // seconds. A breath-length pause after a very short sentence is fine; anything longer isn't.
        assertTrue("Longest silence ${v.longestGapMillis} ms", v.longestGapMillis < 400)
        assertTrue("Total silence ${v.gapMillis} ms", v.gapMillis < 800)
    }

    @Test
    fun stop_silencesAtOnce_andTheNextLineStartsClean() {
        val v = ready()
        val long = "This is a long reply that goes on for quite a while, so that there is plenty of audio " +
            "waiting to be played when the stop button is pressed in the middle of it all."
        v.say(long)
        v.say(long)
        assertTrue(waitUntil(15_000) { v.playedFrames() > 24_000 }) // a second in
        val stopped = SystemClock.elapsedRealtime()
        v.stop()
        assertFalse(v.speaking.value)
        val atStop = v.playedFrames()
        Thread.sleep(300)
        val later = v.playedFrames()
        Thread.sleep(300)
        log("Stop took ${SystemClock.elapsedRealtime() - stopped - 600} ms of the call; frames at stop $atStop, then $later, then ${v.playedFrames()}")
        // Paused and flushed: nothing more comes out (flush may reset the count to 0).
        assertTrue("Sound continued after Stop", later <= atStop)
        assertEquals("Sound continued after Stop", later, v.playedFrames())

        // The next line plays from its own start, with nothing left over from before.
        v.say("Hello again.")
        assertTrue(waitUntil(15_000) { !v.speaking.value })
        val played = v.playedFrames() / 24_000f
        log("After stop, \"Hello again\" played %.2f s".format(played))
        assertTrue("Only the new line should play, not leftovers", played in 0.3f..3f)
    }
}
