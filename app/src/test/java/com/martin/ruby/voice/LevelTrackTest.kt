package com.martin.ruby.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelTrackTest {

    @Test
    fun theLevelHeardIsTheSliceBeingPlayed_notTheOneJustWritten() {
        val track = LevelTrack()
        // 12 slices of 1200 frames, getting louder; the speaker is still on slice 2.
        for (n in 0 until 12) track.add(n * 1200L, (n + 1) * 1200L, n / 12f)
        assertEquals(2 / 12f, track.at(2 * 1200L + 600), 1e-6f)
        assertEquals(11 / 12f, track.at(11 * 1200L), 1e-6f)
        // Past what's been written: a pause between pieces.
        assertEquals(0f, track.at(12 * 1200L + 5))
    }

    @Test
    fun oldSlicesAreForgotten_andClearStartsAgain() {
        val track = LevelTrack(capacity = 4)
        for (n in 0 until 10) track.add(n * 100L, (n + 1) * 100L, 0.5f)
        assertEquals(0f, track.at(50)) // long gone
        assertEquals(0.5f, track.at(950), 1e-6f)
        track.clear()
        assertEquals(0f, track.at(950))
    }

    @Test
    fun loudnessFromSamples() {
        fun tone(amplitude: Float) = FloatArray(1200) { (amplitude * sin(2 * PI * 220 * it / 24000)).toFloat() }
        assertEquals(0f, LevelTrack.levelOf(FloatArray(1200), 0, 1200))
        val quiet = LevelTrack.levelOf(tone(0.01f), 0, 1200)
        val speech = LevelTrack.levelOf(tone(0.15f), 0, 1200)
        val loud = LevelTrack.levelOf(tone(0.9f), 0, 1200)
        assertTrue("quiet $quiet < speech $speech < loud $loud", quiet < speech && speech < loud)
        assertTrue(speech in 0.4f..0.95f)
        assertEquals(1f, loud, 1e-6f)
    }
}
