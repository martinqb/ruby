package com.martin.ruby.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Free mode's words: spotting reminder requests, reading Gemini Nano's answers, the chat prompt. */
class FreeModeTextTest {

    @Test
    fun reminderRequestsAreSpotted() {
        assertEquals(FreeReminders.Kind.SET, FreeReminders.kindOf("Remind me to call Mom at 5:30"))
        assertEquals(FreeReminders.Kind.SET, FreeReminders.kindOf("Can you set a reminder for the dentist tomorrow at 9?"))
        assertEquals(FreeReminders.Kind.SET, FreeReminders.kindOf("Don't let me forget the milk when I get to Grocery"))
        assertEquals(FreeReminders.Kind.LIST, FreeReminders.kindOf("What reminders do I have?"))
        assertEquals(FreeReminders.Kind.LIST, FreeReminders.kindOf("Any reminders set?"))
        assertEquals(FreeReminders.Kind.CANCEL, FreeReminders.kindOf("Cancel the reminder about Mom"))
        assertEquals(FreeReminders.Kind.CANCEL, FreeReminders.kindOf("Please delete my milk reminder"))
        // Ordinary chat isn't.
        assertNull(FreeReminders.kindOf("How far away is Neptune?"))
        assertNull(FreeReminders.kindOf("That reminds me of a song"))
        assertNull(FreeReminders.kindOf("Cancel my plans? No, just chatting"))
    }

    @Test
    fun nanosReminderAnswer_isRead_evenInACodeBlock() {
        val answer = "```json\n{\n  \"text\": \"Call Mom\",\n  \"at\": \"2026-11-24T17:30\",\n  \"place\": null,\n  \"sure\": true\n}\n```"
        assertEquals(FreeReminders.Request("Call Mom", "2026-11-24T17:30", null, true), FreeReminders.request(answer))
        assertEquals(
            FreeReminders.Request("Buy milk", null, "Grocery", true),
            FreeReminders.request("""{"text":"Buy milk","at":"null","place":"Grocery"}"""),
        )
        // Unsure, or no text, or no JSON at all.
        assertFalse(FreeReminders.request("""{"text":"Do the thing","at":null,"place":null,"sure":false}""")!!.sure)
        assertNull(FreeReminders.request("""{"at":"2026-11-24T17:30"}"""))
        assertNull(FreeReminders.request("Sure! I'll remind you."))
        assertNull(FreeReminders.request("{ broken"))
    }

    @Test
    fun whichReminderToCancel() {
        assertEquals(12L, FreeReminders.cancelId("```json\n{\"id\": 12}\n```"))
        assertNull(FreeReminders.cancelId("""{"id": null}"""))
        assertNull(FreeReminders.cancelId("""{"id": -3}"""))
        assertTrue(FreeReminders.cancelPrompt(listOf(12L to "Call Mom", 13L to "Buy milk")).contains("12: Call Mom\n13: Buy milk"))
    }

    @Test
    fun theReminderPrompt_givesTheTimeAndPlaces() {
        val p = FreeReminders.setPrompt("Tue, Nov 24, 2026, 8:49 AM (America/New_York)", listOf("Home", "Grocery"))
        assertTrue(p.contains("Tue, Nov 24, 2026, 8:49 AM (America/New_York)"))
        assertTrue(p.contains("Saved places: Home, Grocery."))
        assertTrue(FreeReminders.setPrompt("now", emptyList()).contains("Saved places: none."))
    }

    @Test
    fun whenFreeModeCant_ClaudeIsOfferedOnlyWithAKey() {
        assertEquals(
            "I can't do that one in Free mode: \"Gym\" isn't one of your saved places. Want me to use Claude for this one?",
            FreeReminders.cantDo("\"Gym\" isn't one of your saved places.", claudeOffered = true),
        )
        assertEquals(
            "I can't do that one in Free mode: the time or place wasn't clear enough.",
            FreeReminders.cantDo("the time or place wasn't clear enough.", claudeOffered = false),
        )
    }

    @Test
    fun theChatPrompt_keepsTheNewestTurns_andEndsWithTheNewMessage() {
        val earlier = listOf(true to "Hi Ruby", false to "Hello! How can I help?", true to "How far is Neptune?", false to "About 4.5 billion km.")
        val p = FreeChatText.prompt(earlier, "[Right now: Still · 8:49 AM]\nAnd Uranus?")
        assertTrue(p, p.startsWith("The conversation so far:\nUser: Hi Ruby\nRuby: Hello! How can I help?\n"))
        assertTrue(p, p.contains("The user's new message:\n[Right now: Still · 8:49 AM]\nAnd Uranus?"))
        assertTrue(p.endsWith("Reply as Ruby."))
        // Too long: the oldest turns go first, the newest stay.
        val long = (1..400).map { (it % 2 == 1) to "Message number $it " + "x".repeat(100) }
        val trimmed = FreeChatText.prompt(long, "Newest")
        assertTrue(trimmed.length < FreeChatText.HISTORY_CHARS + 500)
        assertTrue(trimmed.contains("Message number 400 "))
        assertFalse(trimmed.contains("Message number 1 "))
        // No earlier turns: just the message.
        assertEquals("The user's new message:\nHello\n\nReply as Ruby.", FreeChatText.prompt(emptyList(), "Hello"))
    }

    @Test
    fun theHelperLine_isMartinsWording() {
        assertEquals(
            "Free mode uses the AI built into your phone: private, offline, no cost. Want Ruby at her smartest? " +
                "Switch to Claude (needs your own API key).",
            AiEngine.HELPER,
        )
        assertEquals("Free mode isn't available on this phone.", AiEngine.NOT_ON_THIS_PHONE)
    }
}
