package com.martin.ruby.voice

import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.briefing.Connection
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stopping Ruby hands-free: media keys (the car's pause button, a headset, "Hey Google,
 * stop") sent through Android's own media-key path stop her at once. Plus the offline check
 * behind the briefing's "No internet" note. Plays sound and turns airplane mode on and off,
 * so it runs on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class HandsFreeStopTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun log(message: String) = Log.i("HandsFreeTest", message)

    private fun emulatorOnly() = assumeTrue(
        "Plays sound and changes airplane mode: emulator only",
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
    )

    @Before
    fun ready() {
        emulatorOnly()
        assumeTrue("Needs the Kokoro voice installed", KokoroModel.isInstalled(context))
        instrumentation.runOnMainSync { VoiceSession.start(context) }
        assertTrue("The voice should load", waitUntil(10_000) { VoiceSession.ui.value.usingEngine != null })
    }

    @After
    fun quiet() {
        instrumentation.runOnMainSync { VoiceSession.stopSpeaking() }
    }

    private fun startTalking() {
        instrumentation.runOnMainSync {
            VoiceSession.play(
                "Good morning! It's twenty seven degrees and clear. You have three events today, starting with the " +
                    "team stand-up at nine. Tonight there's a full moon, and Jupiter is high in the east."
            )
        }
        assertTrue("She should be heard", waitUntil(20_000) { VoiceSession.ui.value.sounding })
    }

    /** Presses [keyCode] the way a car or headset does: through Android's media-key dispatch. */
    private fun press(keyCode: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun assertStopsOn(name: String, press: () -> Unit) {
        startTalking()
        val pressed = SystemClock.elapsedRealtime()
        press()
        assertTrue("$name should stop her", waitUntil(2_000) { !VoiceSession.ui.value.speaking })
        log("$name stopped her in ${SystemClock.elapsedRealtime() - pressed} ms")
    }

    @Test
    fun theCarsPauseButton_stopsHer() = assertStopsOn("Pause") { press(KeyEvent.KEYCODE_MEDIA_PAUSE) }

    @Test
    fun playPause_stopsHer() = assertStopsOn("Play/pause") { press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }

    @Test
    fun aHeadsetButton_stopsHer() = assertStopsOn("Headset button") { press(KeyEvent.KEYCODE_HEADSETHOOK) }

    @Test
    fun stop_stopsHer() = assertStopsOn("Stop") { press(KeyEvent.KEYCODE_MEDIA_STOP) }

    @Test
    fun aPauseFromTheSystem_stopsHer() = assertStopsOn("System pause (as from \"Hi Bixby, pause\" or \"Hey Google, stop\")") {
        // The same route a Bluetooth car's or the Assistant's pause takes into the media system.
        instrumentation.uiAutomation.executeShellCommand("cmd media_session dispatch pause").close()
    }

    @Test
    fun whenSheIsQuiet_theButtonsAreLeftAlone() {
        startTalking()
        instrumentation.runOnMainSync { VoiceSession.stopSpeaking() }
        assertTrue(waitUntil(2_000) { !VoiceSession.ui.value.speaking })
        // Not talking: a pause press does nothing to her (it's for the music app again).
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        Thread.sleep(300)
        assertFalse(VoiceSession.ui.value.speaking)
        // And she still talks normally afterwards.
        startTalking()
    }

    @Test
    fun airplaneMode_showsAsNoInternet() {
        try {
            shell("cmd connectivity airplane-mode enable")
            assertTrue("Offline in airplane mode", waitUntil(15_000) { !Connection.isOnline(context) })
        } finally {
            shell("cmd connectivity airplane-mode disable")
        }
        assertTrue("Online again", waitUntil(30_000) { Connection.isOnline(context) })
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
