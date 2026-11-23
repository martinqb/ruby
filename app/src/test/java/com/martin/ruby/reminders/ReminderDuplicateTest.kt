package com.martin.ruby.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderDuplicateTest {

    private val five = 1_700_000_000_000L
    private val oven = Reminder(id = 3, text = "Check the oven", dueMillis = five, createdMillis = 0)
    private val milk = Reminder(id = 4, text = "Buy milk", place = "Store", createdMillis = 0)
    private val pending = listOf(oven, milk)

    @Test
    fun sameTextAndTime_isADuplicate() {
        assertEquals(oven, ReminderText.duplicateOf(pending, "Check the oven", five, null))
    }

    @Test
    fun caseSpacingAndFinalPeriod_dontMatter_andAMinuteOrTwoIsTheSameTime() {
        assertEquals(oven, ReminderText.duplicateOf(pending, "  check  the OVEN. ", five + 90_000, null))
    }

    @Test
    fun aDifferentTime_isntADuplicate() {
        assertNull(ReminderText.duplicateOf(pending, "Check the oven", five + 10 * 60_000, null))
    }

    @Test
    fun differentText_isntADuplicate() {
        assertNull(ReminderText.duplicateOf(pending, "Check the stove", five, null))
    }

    @Test
    fun placeReminders_matchOnThePlace() {
        assertEquals(milk, ReminderText.duplicateOf(pending, "buy milk", null, "store"))
        assertNull(ReminderText.duplicateOf(pending, "Buy milk", null, "Home"))
        // A time reminder with the same text isn't the same as the place one.
        assertNull(ReminderText.duplicateOf(pending, "Buy milk", five, null))
    }

    @Test
    fun onlyWaitingRemindersCount() {
        val done = oven.copy(state = Reminder.STATE_FIRED)
        assertNull(ReminderText.duplicateOf(listOf(done), "Check the oven", five, null))
    }
}
