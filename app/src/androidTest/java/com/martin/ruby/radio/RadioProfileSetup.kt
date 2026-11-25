package com.martin.ruby.radio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sets up Martin's radio profile on his phone, once: WGRR 103.5 as the default, and WEBN 102.7.
 * Not part of the app (new users start with no stations). Runs only when asked, and only if
 * the phone has never saved any stations, so it can't overwrite his changes:
 *   adb shell am instrument -w -e class com.martin.ruby.radio.RadioProfileSetup -e setup martin \
 *     com.martin.ruby.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RadioProfileSetup {

    @Test
    fun martinsStations() = runBlocking {
        assumeTrue("Only with -e setup martin", InstrumentationRegistry.getArguments().getString("setup") == "martin")
        val store = PresetStore(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeTrue("Stations were saved before; leaving them alone", !store.everSaved())
        store.update { Presets.MARTIN }
    }
}
