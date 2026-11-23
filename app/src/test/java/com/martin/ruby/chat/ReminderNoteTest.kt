package com.martin.ruby.chat

import com.martin.ruby.reminders.Reminder
import com.martin.ruby.reminders.ReminderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Ruby used to say a cancelled reminder was "still coming up at 1:25", going by an old tool
 * result in the chat history. Every message now carries the reminders as they are when it's
 * sent, so the newest message always has the current list.
 */
class ReminderNoteTest {

    private val zone = ZoneId.of("America/New_York")
    private val at125 = LocalDateTime.of(2026, 11, 23, 13, 25).atZone(zone).toInstant().toEpochMilli()
    private val oven = Reminder(id = 3, text = "Check the oven", dueMillis = at125, createdMillis = 0)
    private val milk = Reminder(id = 4, text = "Buy milk", place = "Store", createdMillis = 0)

    private fun send(text: String, pending: List<Reminder>) =
        ClaudeChat.compose(text, "At Home", "Mon, Nov 23, 2026, 1:10 PM (America/New_York)", ReminderText.snapshot(pending, zone), null)

    @Test
    fun afterACancel_theNextMessageNoLongerListsIt() {
        val before = send("Remind me at 1:25 to check the oven", listOf(milk))
        val afterSetting = send("Thanks", listOf(oven, milk))
        val afterCancelling = send("Is the oven one still coming up?", listOf(milk)) // cancelled in between

        assertTrue(afterSetting.contains("3: Check the oven (Mon, Nov 23, 2026, 1:25 PM)"))
        assertFalse("A cancelled reminder must not be in the newest note", afterCancelling.contains("Check the oven"))
        assertTrue(afterCancelling.contains("4: Buy milk (on arriving at Store)"))
        assertFalse(before.contains("Check the oven"))
    }

    @Test
    fun theNoteComesBeforeMemoriesAndTheMessage() {
        val sent = ClaudeChat.compose("When's the oven reminder?", null, "1:10 PM", ReminderText.snapshot(listOf(oven), zone), "From Ruby's memory: ...")
        assertEquals(
            "[Right now: 1:10 PM]\n" +
                "[Waiting reminders: 3: Check the oven (Mon, Nov 23, 2026, 1:25 PM).]\n" +
                "[From Ruby's memory: ...]\n\n" +
                "When's the oven reminder?",
            sent,
        )
    }

    @Test
    fun noneWaiting_saysSoRatherThanLeavingItOut() {
        // An empty list is said out loud, so "not in the note" can't be mistaken for "no note".
        assertEquals("Waiting reminders: none.", ReminderText.snapshot(emptyList(), zone))
        assertEquals(
            "Waiting reminders: none.",
            ReminderText.snapshot(listOf(oven.copy(state = Reminder.STATE_CANCELLED), oven.copy(id = 5, state = Reminder.STATE_FIRED)), zone),
        )
    }

    @Test
    fun manyReminders_areCappedAndCounted() {
        val many = (1..25).map { Reminder(id = it.toLong(), text = "Thing $it", place = "Home", createdMillis = 0) }
        val note = ReminderText.snapshot(many, zone)
        assertTrue(note.contains("20: Thing 20"))
        assertFalse(note.contains("21: Thing 21"))
        assertTrue(note.endsWith("; and 5 more (use list_reminders)."))
    }

    @Test
    fun noNote_whenRemindersCouldntBeRead() {
        assertEquals("[Right now: 1:10 PM]\nHi", ClaudeChat.compose("Hi", null, "1:10 PM", null, null))
    }
}
