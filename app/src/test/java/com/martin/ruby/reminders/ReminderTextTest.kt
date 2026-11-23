package com.martin.ruby.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderTextTest {

    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDateTime.of(2026, 11, 23, 15, 42).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun localTime_isReadInThePhonesZone() {
        val parsed = ReminderText.parse("2026-11-24T17:00", now, zone)
        val expected = LocalDateTime.of(2026, 11, 24, 17, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(ReminderText.Parsed.Ok(expected), parsed)
        assertEquals("Tue, Nov 24, 2026, 5:00 PM", ReminderText.time(expected, zone))
    }

    @Test
    fun secondsAreOptional() {
        assertTrue(ReminderText.parse("2026-11-24T17:00:30", now, zone) is ReminderText.Parsed.Ok)
    }

    @Test
    fun past_isRefusedWithTheCurrentTime() {
        val parsed = ReminderText.parse("2026-11-23T09:00", now, zone)
        assertTrue(parsed is ReminderText.Parsed.Bad)
        assertTrue((parsed as ReminderText.Parsed.Bad).why.contains("Mon, Nov 23, 2026, 3:42 PM"))
    }

    @Test
    fun aMomentAgo_becomesNow() {
        // Claude rounding "now" down a few seconds shouldn't fail.
        val later = now + 20_000
        assertEquals(ReminderText.Parsed.Ok(later), ReminderText.parse("2026-11-23T15:42", later, zone))
    }

    @Test
    fun nonsenseAndFarFuture_areRefused() {
        assertTrue(ReminderText.parse("tomorrow at 5", now, zone) is ReminderText.Parsed.Bad)
        assertTrue(ReminderText.parse("2030-01-01T09:00", now, zone) is ReminderText.Parsed.Bad)
    }
}
