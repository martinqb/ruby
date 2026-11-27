package com.martin.ruby.listening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningRulesTest {

    @Test
    fun theModesCombine_andTheQuickSwitchPausesThemAll() {
        val all = ListeningPrefs(conversation = true, wakeWord = true, fullDuplex = true)
        assertTrue(all.active)
        assertTrue(all.needsModel)
        // Off for battery: nothing runs, but the ticks are kept.
        val off = all.copy(on = false)
        assertFalse(off.active)
        assertTrue(off.anyMode)
        // Conversation mode alone uses Android's recognizer, not the listening model.
        assertFalse(ListeningPrefs(conversation = true).needsModel)
        assertFalse(ListeningPrefs().active)
        assertEquals("Hey Ruby", ListeningPrefs().wakePhrase)
    }

    @Test
    fun wakePhrase_isMatchedAsPlainWords() {
        assertEquals("hey ruby", WakePhrase.normalize("  Hey, Ruby! "))
        assertEquals("ok computer", WakePhrase.normalize("OK   Computer."))
        assertEquals("what's up", WakePhrase.normalize("What’s up?"))
        assertTrue(WakePhrase.heard("Hey Ruby", "hey ruby"))
        assertTrue(WakePhrase.heard("Hey Ruby", "um hey ruby what's the time"))
        assertFalse(WakePhrase.heard("Hey Ruby", "hey"))
        assertFalse(WakePhrase.heard("Hey Ruby", "ruby hey"))
        assertFalse(WakePhrase.heard("Hey Ruby", "hey rubies"))
        assertFalse(WakePhrase.heard("Hey Ruby", ""))
        assertEquals("[\"hey ruby\", \"[unk]\"]", WakePhrase.grammar("Hey, Ruby"))
    }

    @Test
    fun wakePhrase_canBeAnyTypedWords_withinReason() {
        assertNull(WakePhrase.problem("Hey Ruby"))
        assertNull(WakePhrase.problem("Computer"))
        assertNull(WakePhrase.problem("OK Jarvis"))
        assertEquals("Type a wake word, like \"Hey Ruby\".", WakePhrase.problem("  !! "))
        assertEquals("Use letters only (write numbers as words).", WakePhrase.problem("Hey R2"))
        assertEquals("Keep it to 4 words or fewer.", WakePhrase.problem("hey there my lovely ruby"))
        assertEquals("Use something longer, like \"Hey Ruby\", so she isn't woken by accident.", WakePhrase.problem("Yo"))
        // Words the listening model doesn't know can't be heard, so they're pointed out.
        val known = setOf("hey", "ruby", "jarvis")
        assertEquals(emptyList<String>(), WakePhrase.unknownWords("Hey Ruby", known::contains))
        assertEquals(listOf("zorbo"), WakePhrase.unknownWords("Hey Zorbo", known::contains))
    }

    @Test
    fun bargeIn_isTheUser_notRubysOwnEcho() {
        val saying = "Your dentist appointment is on Tuesday at nine thirty in the morning"
        // Her own words coming back through the speaker don't stop her.
        assertFalse(BargeIn.isUser("dentist appointment tuesday", saying))
        assertFalse(BargeIn.isUser("the morning", saying))
        // The user's own words do.
        assertTrue(BargeIn.isUser("what about thursday", saying))
        assertTrue(BargeIn.isUser("can you move it", saying))
        // One word only stops her if it's a word for stopping.
        assertTrue(BargeIn.isUser("stop", saying))
        assertTrue(BargeIn.isUser("wait", saying))
        assertFalse(BargeIn.isUser("huh", saying))
        assertFalse(BargeIn.isUser("the", saying))
        // ...unless she's saying that word herself.
        assertFalse(BargeIn.isUser("stop", "The bus stop is on Main Street"))
        assertFalse(BargeIn.isUser("", saying))
    }

    @Test
    fun conversation_reopensOnlyWhenRubyIsDone() {
        assertTrue(ConversationRules.reopen(conversation = true, lastTurnSpoken = true, answering = false, speaking = false, listening = false))
        assertFalse(ConversationRules.reopen(conversation = false, lastTurnSpoken = true, answering = false, speaking = false, listening = false))
        // A typed message, or after the morning briefing: no.
        assertFalse(ConversationRules.reopen(conversation = true, lastTurnSpoken = false, answering = false, speaking = false, listening = false))
        assertFalse(ConversationRules.reopen(conversation = true, lastTurnSpoken = true, answering = true, speaking = false, listening = false))
        assertFalse(ConversationRules.reopen(conversation = true, lastTurnSpoken = true, answering = false, speaking = true, listening = false))
        assertFalse(ConversationRules.reopen(conversation = true, lastTurnSpoken = true, answering = false, speaking = false, listening = true))
    }

    @Test
    fun conversation_endsWhenTheUserSaysSo() {
        assertTrue(ConversationRules.endsConversation("That's all"))
        assertTrue(ConversationRules.endsConversation("that's all, thanks"))
        assertTrue(ConversationRules.endsConversation("Okay thanks, that's all"))
        assertTrue(ConversationRules.endsConversation("Stop listening."))
        assertTrue(ConversationRules.endsConversation("Goodbye Ruby"))
        assertTrue(ConversationRules.endsConversation("no thanks"))
        assertFalse(ConversationRules.endsConversation("That's all I needed to know about the weather?"))
        assertFalse(ConversationRules.endsConversation("Thanks"))
        assertFalse(ConversationRules.endsConversation("Play the radio"))
    }

    @Test
    fun theMic_followsTheModesAndWhatRubyIsDoing() {
        val wake = ListeningPrefs(wakeWord = true)
        val duplex = ListeningPrefs(fullDuplex = true)
        val both = ListeningPrefs(wakeWord = true, fullDuplex = true)
        assertEquals(MicUse.WAKE, ListeningPlan.micUse(wake, ready = true, speaking = false, userListening = false))
        // The wake phrase works while she talks too.
        assertEquals(MicUse.WAKE, ListeningPlan.micUse(wake, ready = true, speaking = true, userListening = false))
        assertEquals(MicUse.BARGE_IN, ListeningPlan.micUse(duplex, ready = true, speaking = true, userListening = false))
        assertEquals(MicUse.NONE, ListeningPlan.micUse(duplex, ready = true, speaking = false, userListening = false))
        assertEquals(MicUse.BARGE_IN, ListeningPlan.micUse(both, ready = true, speaking = true, userListening = false))
        // The Talk button's listening has the mic: the modes step aside.
        assertEquals(MicUse.NONE, ListeningPlan.micUse(both, ready = true, speaking = false, userListening = true))
        // Not ready (no mic permission or model), or switched off for battery.
        assertEquals(MicUse.NONE, ListeningPlan.micUse(both, ready = false, speaking = true, userListening = false))
        assertEquals(MicUse.NONE, ListeningPlan.micUse(both.copy(on = false), ready = true, speaking = true, userListening = false))
        // Conversation mode alone doesn't hold the mic between turns.
        assertEquals(MicUse.NONE, ListeningPlan.micUse(ListeningPrefs(conversation = true), ready = true, speaking = false, userListening = false))
    }

    @Test
    fun theNotification_saysWhatsListening() {
        assertEquals(
            "Listening for \"Hey Ruby\", conversation mode, you can talk over her.",
            ListeningPlan.notification(ListeningPrefs(conversation = true, wakeWord = true, fullDuplex = true)),
        )
        assertEquals("Conversation mode.", ListeningPlan.notification(ListeningPrefs(conversation = true)))
        assertEquals("Listening for \"OK Jarvis\".", ListeningPlan.notification(ListeningPrefs(wakeWord = true, wakePhrase = " OK Jarvis ")))
    }
}
