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
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Play the radio", radioOn = false))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("put on the radio please", radioOn = false))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Turn on the radio.", radioOn = false))
    }

    @Test
    fun stationsByNameOrFrequency_asSpeechRecognitionWritesThem() {
        assertEquals(play(Stations.WEBN), RadioCommands.parse("Play WEBN", radioOn = false))
        assertEquals(play(Stations.WEBN), RadioCommands.parse("play web n", radioOn = false))
        assertEquals(play(Stations.WEBN), RadioCommands.parse("put on 102.7", radioOn = false))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("Play WGRR", radioOn = true))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("switch to 103.5", radioOn = true))
        assertEquals(play(Stations.WGRR), RadioCommands.parse("play w g r r", radioOn = false))
    }

    @Test
    fun stop() {
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Stop the radio", radioOn = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("turn off the radio", radioOn = false))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Radio off!", radioOn = true))
        // A bare "stop" is for the radio only while it plays; otherwise it's ordinary chat.
        assertEquals(RadioCommand.Stop, RadioCommands.parse("Stop", radioOn = true))
        assertEquals(RadioCommand.Stop, RadioCommands.parse("pause it please", radioOn = true))
        assertNull(RadioCommands.parse("Stop", radioOn = false))
    }

    @Test
    fun ordinaryMessages_areLeftToTheChat() {
        assertNull(RadioCommands.parse("Play a song by the Beatles", radioOn = false))
        assertNull(RadioCommands.parse("What's on the radio tonight?", radioOn = false))
        assertNull(RadioCommands.parse("Remind me at 10:35 to call Mom", radioOn = false))
        assertNull(RadioCommands.parse("Stop reminding me about the oven", radioOn = true))
        assertNull(RadioCommands.parse("I love the radio", radioOn = false))
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
    fun afterTheBriefing_onlyWhenOn_inTheCar_andNotStopped() {
        assertTrue(RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = false, inCar = true, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = true, inCar = false, stoppedByUser = false))
        assertFalse(RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = true))
    }
}
