package com.martin.ruby.radio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Searching the live Radio Browser directory. Read only, so it's safe on a real phone. Skipped
 * where the network can't reach it (the emulator on this PC can't, through its antivirus).
 */
@RunWith(AndroidJUnit4::class)
class RadioNetworkTest {

    private fun search(by: RadioBrowser.By, text: String): List<Station> = runBlocking {
        try {
            RadioSearch.search(by, text)
        } catch (e: IOException) {
            assumeTrue("Radio Browser can't be reached here: ${e.message}", false)
            emptyList()
        }
    }

    @Test
    fun byName_byPlace_byGenre() {
        val name = search(RadioBrowser.By.NAME, "WVXU")
        val place = search(RadioBrowser.By.PLACE, "Ohio")
        val genre = search(RadioBrowser.By.GENRE, "jazz")
        for ((what, found) in listOf("WVXU" to name, "Ohio" to place, "jazz" to genre)) {
            Log.i("RadioNet", "$what: ${found.size} found; first: ${found.take(4).map { "${it.name} (${if (it.playsInRuby) "plays in Ruby" else "opens elsewhere"})" }}")
        }
        assertTrue(name.isNotEmpty() && place.size > 5 && genre.size > 5)
        assertTrue("Most results play inside Ruby", genre.count { it.playsInRuby } > genre.size / 2)
        // WGRR is found too, from Ruby's own list.
        assertTrue(search(RadioBrowser.By.NAME, "WGRR").any { it.id == "wgrr" })
    }
}
