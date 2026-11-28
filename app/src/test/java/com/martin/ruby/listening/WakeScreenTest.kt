package com.martin.ruby.listening

import com.martin.ruby.chat.ChatUi
import com.martin.ruby.ui.WakeActivity
import com.martin.ruby.voice.VoiceUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bringing Ruby up on the wake word, and when her view keeps the screen on. */
class WakeScreenTest {

    @Test
    fun alreadyOnScreen_opensNothing() {
        assertEquals(WakeScreen.Route.ALREADY_SHOWN, WakeScreen.route(rubyShown = true, canOpenFromBackground = true))
        assertEquals(WakeScreen.Route.ALREADY_SHOWN, WakeScreen.route(rubyShown = true, canOpenFromBackground = false))
    }

    @Test
    fun withDisplayOverOtherApps_herViewOpens_elseAFullScreenNotification_elseSheWakesTheScreen() {
        assertEquals(WakeScreen.Route.OPEN_VIEW, WakeScreen.route(rubyShown = false, canOpenFromBackground = true, canFullScreen = false))
        assertEquals(WakeScreen.Route.FULL_SCREEN_NOTIFICATION, WakeScreen.route(rubyShown = false, canOpenFromBackground = false, canFullScreen = true))
        // Never a black screen: with neither allowed she still turns it on.
        assertEquals(WakeScreen.Route.WAKE_AND_NOTIFY, WakeScreen.route(rubyShown = false, canOpenFromBackground = false, canFullScreen = false))
    }

    @Test
    fun theAsks_overlayFirst_thenFullScreen_eachOnce() {
        assertEquals(null, WakeScreen.ask(canOverlay = true, overlayAsked = false, canFullScreen = false, fullScreenAsked = false))
        assertEquals(WakeScreen.Ask.OVERLAY, WakeScreen.ask(canOverlay = false, overlayAsked = false, canFullScreen = false, fullScreenAsked = false))
        // "Not now" to the first: then the fallback's access, once.
        assertEquals(WakeScreen.Ask.FULL_SCREEN, WakeScreen.ask(canOverlay = false, overlayAsked = true, canFullScreen = false, fullScreenAsked = false))
        assertEquals(null, WakeScreen.ask(canOverlay = false, overlayAsked = true, canFullScreen = false, fullScreenAsked = true))
        assertEquals(null, WakeScreen.ask(canOverlay = false, overlayAsked = true, canFullScreen = true, fullScreenAsked = false))
    }

    @Test
    fun theScreenStaysOn_whileTheConversationGoes_thenTimesOutAsUsual() {
        assertTrue(WakeScreen.keepScreenOn(active = true, idleForMs = 0))
        // The gap between her answer and the mic reopening doesn't let it go dark.
        assertTrue(WakeScreen.keepScreenOn(active = false, idleForMs = 1_000))
        assertFalse(WakeScreen.keepScreenOn(active = false, idleForMs = WakeScreen.GRACE_MS))
        assertFalse(WakeScreen.keepScreenOn(active = false, idleForMs = 60_000))
    }

    @Test
    fun theConversationIsGoing_whileListeningThinkingOrTalking() {
        val idle = VoiceUi()
        assertFalse(WakeActivity.conversationActive(ChatUi(), idle))
        assertTrue(WakeActivity.conversationActive(ChatUi(), idle.copy(listening = true)))
        assertTrue(WakeActivity.conversationActive(ChatUi(sending = true), idle))
        assertTrue(WakeActivity.conversationActive(ChatUi(), idle.copy(speaking = true)))
        assertTrue(WakeActivity.conversationActive(ChatUi(), idle.copy(readingReply = true)))
    }
}
