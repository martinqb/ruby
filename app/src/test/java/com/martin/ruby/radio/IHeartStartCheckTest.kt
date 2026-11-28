package com.martin.ruby.radio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opening a station in iHeartRadio: did it really start, and what Ruby says about it. */
class IHeartStartCheckTest {

    /** Waits without sleeping: the condition is looked at once, as at the end of the wait. */
    private val noSleep: suspend (Long, () -> Boolean) -> Boolean = { _, done -> done() }

    @Test
    fun playingByItself_noPlayPressed() = runBlocking {
        var pressed = 0
        val start = IHeartStartCheck.run(playing = { true }, pressPlay = { pressed++ }, waitFor = noSleep)
        assertEquals(IHeartStart.PLAYING, start)
        assertEquals(0, pressed)
    }

    @Test
    fun notPlaying_rubyPressesPlayOnce_andItStarts() = runBlocking {
        var playing = false
        var pressed = 0
        val start = IHeartStartCheck.run(playing = { playing }, pressPlay = { pressed++; playing = true }, waitFor = noSleep)
        assertEquals(IHeartStart.PLAYING_AFTER_PRESS, start)
        assertEquals(1, pressed)
    }

    @Test
    fun stillNotPlaying_afterThePress_isAFailure() = runBlocking {
        var pressed = 0
        val start = IHeartStartCheck.run(playing = { false }, pressPlay = { pressed++ }, waitFor = noSleep)
        assertEquals(IHeartStart.NOT_PLAYING, start)
        assertEquals("only one press", 1, pressed)
    }

    @Test
    fun itWaitsForAutoplay_thenForThePress() = runBlocking {
        val waits = mutableListOf<Long>()
        IHeartStartCheck.run(playing = { false }, pressPlay = {}, waitFor = { ms, done -> waits += ms; done() })
        assertEquals(listOf(IHeartStartCheck.AUTOPLAY_MS, IHeartStartCheck.AFTER_PRESS_MS), waits)
        // Long enough for iHeartRadio to buffer, short enough not to leave the car silent for long.
        assertTrue(IHeartStartCheck.AUTOPLAY_MS in 3_000L..10_000L)
        assertTrue(IHeartStartCheck.AUTOPLAY_MS + IHeartStartCheck.AFTER_PRESS_MS <= 15_000L)
    }

    @Test
    fun whatRubySays() {
        val webn = Stations.WEBN
        assertEquals("WEBN 102.7 is playing in iHeartRadio.", RadioCommands.iHeartStartReply(webn, IHeartStart.PLAYING))
        assertEquals("WEBN 102.7 is playing in iHeartRadio.", RadioCommands.iHeartStartReply(webn, IHeartStart.PLAYING_AFTER_PRESS))
        val failed = RadioCommands.iHeartStartReply(webn, IHeartStart.NOT_PLAYING)
        assertEquals(
            "WEBN 102.7 opened in iHeartRadio, but it didn't start playing, even after I pressed play. Press play there, or ask me again.",
            failed,
        )
        // Never claims it's playing, and assumes no steering wheel button.
        assertFalse(failed.contains("steering", ignoreCase = true))
        assertEquals("WEBN 102.7 stopped in iHeartRadio.", RadioCommands.iHeartStoppedReply(webn))
    }
}
