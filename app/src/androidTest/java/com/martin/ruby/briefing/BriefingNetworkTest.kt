package com.martin.ruby.briefing

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The live sources: Open-Meteo and Wikipedia. Read only, with a public test place (not the
 * user's), so it's safe on a real phone. Skipped where the network can't reach them (the
 * emulator on this PC can't, through its antivirus).
 */
@RunWith(AndroidJUnit4::class)
class BriefingNetworkTest {

    private fun log(message: String) = Log.i("BriefingNet", message)

    private fun <T> live(name: String, fetch: () -> T): T = try {
        fetch()
    } catch (e: IOException) {
        assumeTrue("$name can't be reached here: ${e.message}", false)
        throw e
    }

    @Test
    fun step3_theForecast_fromOpenMeteo() {
        // Greenwich, London: a public test place.
        val f = live("Open-Meteo") { OpenMeteo.fetch(51.48, 0.0, "C") }
        log("Now ${f.nowTemp}°${f.unit} ${f.nowSky}; high ${f.high}, low ${f.low}; ${f.hours.size} hours")
        assertTrue("Two days of hours", f.hours.size >= 47)
        assertTrue(f.hours.any { it.cloud != null })
        val notes = HeadsUp.notes(f, emptyList(), LocalDateTime.now())
        log("Heads-up for Greenwich: $notes")
        assertTrue(notes.size <= HeadsUp.MAX_NOTES)
    }

    @Test
    fun step6_musicOnThisDay_fromWikipedia() {
        val moments = live("Wikipedia") { MusicOnThisDay.fetch(LocalDate.now()) }
        log("${moments.size} music moments today; first: ${moments.take(3)}")
        assertTrue("Some music history on any date", moments.isNotEmpty())
        assertTrue(MusicOnThisDay.candidates(moments).size <= MusicOnThisDay.MAX_CANDIDATES)
    }
}
