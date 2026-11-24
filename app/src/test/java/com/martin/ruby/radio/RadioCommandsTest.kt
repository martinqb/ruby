package com.martin.ruby.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCommandsTest {

    private fun play(station: Station) = RadioCommand.Play(station)

    @Test
    fun playTheRadio_isWGRR_byDefault() {
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Play the radio", playing = false))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("put on the radio please", playing = false))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Turn on the radio.", playing = false))
    }

    @Test
    fun stationsByNameOrFrequency_asSpeechRecognitionWritesThem() {
        assertEquals(play(Stations.WEBN), RadioCommands.parse("Play WEBN", playing = false))
        assertEquals(play(Stations.WEBN), RadioCommands.parse("play web n", playing = false))
        assertEquals(play(Stations.WEBN), RadioCommands.parse("put on 102.7", playing = false))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Play WGRR", playing = true))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("switch to 103.5", playing = true))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("play w g r r", playing = false))
    }

    @Test
    fun stop() {
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Stop the radio", playing = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("turn off the radio", playing = false))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Radio off!", playing = true))
        // A bare "stop" is for the radio only while it plays; otherwise it's ordinary chat.
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Stop", playing = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("pause it please", playing = true))
        assertNull(RadioCommands.parse("Stop", playing = false))
    }

    @Test
    fun ordinaryMessages_areLeftToTheChat() {
        assertNull(RadioCommands.parse("Play a song by the Beatles", playing = false))
        assertNull(RadioCommands.parse("What's on the radio tonight?", playing = false))
        assertNull(RadioCommands.parse("Remind me at 10:35 to call Mom", playing = false))
        assertNull(RadioCommands.parse("Stop reminding me about the oven", playing = true))
        assertNull(RadioCommands.parse("I love the radio", playing = false))
    }

    @Test
    fun theStations() {
        assertEquals(Stations.WGRR, Stations.DEFAULT)
        assertTrue(Stations.WGRR.playsInRuby)
        assertTrue(Stations.WGRR.stream!!.contains("streamtheworld.com") && Stations.WGRR.stream!!.contains("WGRRFM"))
        // WEBN's only stream is iHeart's own player stream, so it opens in iHeartRadio.
        assertFalse(Stations.WEBN.playsInRuby)
        assertEquals(1701, Stations.WEBN.iHeartId)
    }

    @Test
    fun stopWhileAnotherAppPlays_isForThePhone_notTheAI() {
        // WEBN in iHeartRadio counts as playing: "stop" is a radio command, not chat.
        assertEquals(RadioCommand.Stop, RadioCommands.parse("stop", playing = true))
        assertEquals("Paused WEBN 102.7 in iHeartRadio.", RadioCommands.pauseReply(Stations.WEBN, stopped = true))
        assertEquals(
            "WEBN 102.7 is playing in iHeartRadio. Pause it there or with your steering wheel.",
            RadioCommands.pauseReply(Stations.WEBN, stopped = false),
        )
    }

    @Test
    fun theNoteForTheAI_saysWhatThePhoneReallyDid() {
        assertNull(RadioCommands.note(null, emptyList()))
        assertEquals(
            "Radio (done by the phone; this really happened): WEBN 102.7 was opened in the iHeartRadio app. " +
                "Recently: 9:10 AM: played WGRR 103.5 in Ruby; 9:11 AM: opened WEBN 102.7 in the iHeartRadio app.",
            RadioCommands.note(
                "WEBN 102.7 was opened in the iHeartRadio app",
                listOf("9:10 AM: played WGRR 103.5 in Ruby", "9:11 AM: opened WEBN 102.7 in the iHeartRadio app"),
            ),
        )
        // It goes with each message, after the reminders, in both engines (Free mode sends the same text).
        val sent = com.martin.ruby.chat.ClaudeChat.compose("stop", null, "9:12 AM", "Waiting reminders: none.", null, null, radio = "Radio (done by the phone; this really happened): x.")
        assertTrue(sent, sent.contains("[Waiting reminders: none.]\n[Radio (done by the phone; this really happened): x.]\nstop"))
        assertTrue(com.martin.ruby.ai.FreeChatText.SYSTEM_PROMPT.contains("never deny it"))
    }

    @Test
    fun aDroppedStream_isTriedAgain_withGrowingGaps_thenGivenUp() {
        val r = Reconnect()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L), List(5) { r.next(retriable = true) })
        assertNull("gives up after five tries", r.next(retriable = true))
        r.playing() // back on air: starts over
        assertEquals(2_000L, r.next(retriable = true))
        assertNull("a bad station address isn't retried", Reconnect().next(retriable = false))
        // Network and stream errors are worth retrying; a broken file format isn't.
        assertTrue(Reconnect.retriable(androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(Reconnect.retriable(androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertFalse(Reconnect.retriable(androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }

    @Test
    fun afterTheBriefing_onlyWhenOn_inTheCar_andNotStopped() {
        assertTrue(RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = false, inCar = true, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = true, inCar = false, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = true))
    }
}
