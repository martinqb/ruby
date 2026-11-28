package com.martin.ruby.listening

import android.app.KeyguardManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.ui.WAKE_ORB_TAG
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The wake word with the screen off: the screen comes on and Ruby's small view shows over the
 * lock screen; tapping her orb goes through unlocking (the emulator's lock has no PIN, so it
 * unlocks at once) and opens her full screen. Turns the screen off: emulator only.
 */
@RunWith(AndroidJUnit4::class)
class WakeViewDeviceTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun emulatorOnly() = assumeTrue(
        "Turns the screen off: emulator only",
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
    )

    private val power get() = context.getSystemService(PowerManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)

    /** The emulator starts with its lock screen off: a swipe lock (no PIN) for these tests. */
    @org.junit.Before
    fun lockScreenOn() {
        lockWasDisabled = shell("locksettings get-disabled").trim() == "true"
        shell("locksettings set-disabled false")
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    private var lockWasDisabled = false

    @After
    fun wakeUp() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        if (lockWasDisabled) shell("locksettings set-disabled true")
    }

    @Test
    fun screenOffAndLocked_theWakeWordShowsRuby_andTheOrbUnlocksThenOpensHer() {
        emulatorOnly()
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        assertTrue(waitUntil(3_000) { WakeScreen.canOpenFromBackground(context) })
        // Something in the conversation, answered on the phone (no AI needed).
        instrumentation.runOnMainSync {
            ChatSession.newConversation()
            ChatSession.send(context, "Turn off the radio")
        }
        assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })

        shell("input keyevent KEYCODE_SLEEP")
        assertTrue("screen off", waitUntil(5_000) { !power.isInteractive })
        assertTrue("locked", waitUntil(5_000) { keyguard.isKeyguardLocked })

        // The wake word.
        instrumentation.runOnMainSync { WakeScreen.show(context) }
        assertTrue("the screen comes on", waitUntil(5_000) { power.isInteractive })
        assertTrue("her view shows", waitUntil(5_000) { WakeScreen.viewShown })
        assertTrue("still locked: she never unlocks it", keyguard.isKeyguardLocked)
        // The orb animates without end: step the clock by hand so the test can look.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        assertTrue(compose.onAllNodesWithTag("wake-turn").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithTag(WAKE_ORB_TAG).assertExists()

        // Said again while she's showing: nothing new opens.
        assertTrue(WakeScreen.route(WakeScreen.mainShown || WakeScreen.viewShown, true) == WakeScreen.Route.ALREADY_SHOWN)

        // Tapping the orb: unlock (no PIN here), then her full screen.
        compose.onNodeWithTag(WAKE_ORB_TAG).performClick()
        compose.mainClock.advanceTimeBy(500)
        assertTrue("her full screen opens once unlocked", waitUntil(10_000) { WakeScreen.mainShown })
        assertFalse(keyguard.isKeyguardLocked)
        assertFalse("the small view closed", WakeScreen.viewShown)
        instrumentation.runOnMainSync { ChatSession.newConversation() }
    }

    @Test
    fun withoutDisplayOverOtherApps_aNotificationShowsHerInstead() {
        emulatorOnly()
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW deny")
        assertTrue(waitUntil(3_000) { !WakeScreen.canOpenFromBackground(context) })
        shell("input keyevent KEYCODE_HOME") // Ruby not on screen
        assertTrue(waitUntil(3_000) { !WakeScreen.mainShown && !WakeScreen.viewShown })
        assertTrue(WakeScreen.route(false, WakeScreen.canOpenFromBackground(context)) == WakeScreen.Route.NOTIFICATION)
        instrumentation.runOnMainSync { WakeScreen.show(context) }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        assertTrue("the notification is up, or it opened her view", waitUntil(5_000) {
            manager.activeNotifications.any { it.id == 8 } || WakeScreen.viewShown
        })
        WakeScreen.clearNotification(context)
    }
}
