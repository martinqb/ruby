package com.martin.ruby.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarTextTest {

    private val zone = ZoneId.of("America/New_York")
    private val google = PhoneCalendar(1, "martin@gmail.com", "martin@gmail.com", "com.google", primary = true)
    private val samsung = PhoneCalendar(2, "My calendar", "martin@samsung.com", "com.osp.app.signin")
    private val local = PhoneCalendar(3, "Family", "Phone", "LOCAL")
    private val calendars = listOf(samsung, google, local)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun dayUtc(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun calendarNames_asRubySaysThem() {
        assertEquals("Google Calendar (martin@gmail.com)", google.label)
        assertEquals("Samsung Calendar", samsung.label)
        assertEquals("Family (on this phone)", local.label)
    }

    @Test
    fun whenText_timed_allDay_andMultiDay() {
        assertEquals("Wednesday, December 2 at 9:30 AM to 10:30 AM", CalendarText.whenText(at(2026, 12, 2, 9, 30), at(2026, 12, 2, 10, 30), false, zone))
        assertEquals("Wednesday, December 2, all day", CalendarText.whenText(dayUtc(2026, 12, 2), dayUtc(2026, 12, 3), true, zone))
        assertEquals("Wednesday, December 2 to Friday, December 4, all day", CalendarText.whenText(dayUtc(2026, 12, 2), dayUtc(2026, 12, 5), true, zone))
    }

    @Test
    fun rubyAsks_withTitleDateTimeAndCalendar_beforeSaving() {
        val add = CalendarAction.Add(EventDraft("Dentist", at(2026, 12, 2, 9, 30), at(2026, 12, 2, 10, 30), false, google.id, "Main Street Dental"))
        assertEquals(
            "Dentist, Wednesday, December 2 at 9:30 AM to 10:30 AM, at Main Street Dental, on Google Calendar (martin@gmail.com). Shall I save it?",
            CalendarText.confirm(add, calendars, zone),
        )
        val event = CalEvent(7, "Dentist", at(2026, 12, 2, 9, 30), at(2026, 12, 2, 10, 30), false, google.id)
        val change = CalendarAction.Change(event, EventDraft("Dentist", at(2026, 12, 3, 10, 0), at(2026, 12, 3, 11, 0), false, google.id))
        assertEquals(
            "Change Dentist, Wednesday, December 2 at 9:30 AM to 10:30 AM, to Dentist, Thursday, December 3 at 10:00 AM to 11:00 AM? Shall I save the change?",
            CalendarText.confirm(change, calendars, zone),
        )
        assertEquals(
            "Delete Dentist, Wednesday, December 2 at 9:30 AM to 10:30 AM, from Google Calendar (martin@gmail.com)?",
            CalendarText.confirm(CalendarAction.Delete(event), calendars, zone),
        )
        assertEquals("Saved: Dentist, Wednesday, December 2 at 9:30 AM to 10:30 AM, on Google Calendar (martin@gmail.com).", CalendarText.done(add, calendars, zone))
    }

    @Test
    fun yesAndNo() {
        for (yes in listOf("Yes", "yes please", "Yeah.", "Save it", "go ahead", "OK", "sure", "Delete it")) assertTrue(yes, CalendarText.isYes(yes))
        for (no in listOf("No", "nope", "Cancel", "never mind", "Don't", "No thanks")) assertTrue(no, CalendarText.isNo(no))
        // Anything else isn't an answer (a correction goes to the AI).
        assertFalse(CalendarText.isYes("Yes but make it 10"))
        assertFalse(CalendarText.isNo("No, make it Wednesday"))
    }

    @Test
    fun whichCalendar() {
        assertEquals(samsung, CalendarText.pick("my Samsung calendar", calendars, null))
        assertEquals(google, CalendarText.pick("Google", calendars, null))
        assertEquals(local, CalendarText.pick("family", calendars, null))
        assertEquals(local, CalendarText.pick(null, calendars, defaultId = 3)) // the chosen default
        assertEquals(google, CalendarText.pick(null, calendars, null)) // else the primary Google one
        assertNull(CalendarText.pick("work", calendars, null)) // named but not there
        assertNull(CalendarText.pick(null, emptyList(), null))
        assertEquals(
            "Calendars: Samsung Calendar; Google Calendar (martin@gmail.com) [default]; Family (on this phone).",
            CalendarText.note(calendars, null),
        )
    }

    @Test
    fun eventTimes_fromWhatTheAIGave() {
        assertEquals(Triple(at(2026, 12, 2, 9, 30), at(2026, 12, 2, 10, 30), false), CalendarText.times("2026-12-02", "09:30", null, zone))
        assertEquals(Triple(at(2026, 12, 2, 9, 30), at(2026, 12, 2, 9, 45), false), CalendarText.times("2026-12-02", "09:30", 15, zone))
        assertEquals(Triple(dayUtc(2026, 12, 2), dayUtc(2026, 12, 3), true), CalendarText.times("2026-12-02", null, null, zone))
        assertEquals("\"next Tuesday\" isn't a date.", runCatching { CalendarText.times("next Tuesday", null, null, zone) }.exceptionOrNull()?.message)
        assertEquals("\"half nine\" isn't a time.", runCatching { CalendarText.times("2026-12-02", "half nine", null, zone) }.exceptionOrNull()?.message)
    }

    @Test
    fun whatsOn() {
        val events = listOf(
            CalEvent(1, "Stand-up", at(2026, 12, 2, 9, 0), at(2026, 12, 2, 9, 15), false, google.id),
            CalEvent(2, "Mom's birthday", dayUtc(2026, 12, 2), dayUtc(2026, 12, 3), true, samsung.id),
        )
        assertEquals(
            "2 things on your calendar on Wednesday, December 2:\n• Mom's birthday, all day (Samsung Calendar)\n• Stand-up, 9:00 AM to 9:15 AM (Google Calendar (martin@gmail.com))",
            CalendarText.listing(events, "on Wednesday, December 2", calendars, zone),
        )
        assertEquals("Nothing on your calendar tomorrow.", CalendarText.listing(emptyList(), "tomorrow", calendars, zone))
    }

    @Test
    fun freeMode_spotsCalendarRequests_andReadsNanosAnswers() {
        assertEquals(CalendarText.Kind.ADD, CalendarText.kindOf("Add the dentist to my calendar Tuesday at 9:30"))
        assertEquals(CalendarText.Kind.ADD, CalendarText.kindOf("Schedule a meeting with Sam tomorrow at 3"))
        assertEquals(CalendarText.Kind.LIST, CalendarText.kindOf("What's on my calendar tomorrow?"))
        assertEquals(CalendarText.Kind.LIST, CalendarText.kindOf("Do I have any appointments on Friday?"))
        assertEquals(CalendarText.Kind.LIST, CalendarText.kindOf("What's my schedule tomorrow?"))
        assertEquals(CalendarText.Kind.CHANGE, CalendarText.kindOf("Move my dentist appointment to Wednesday at 10"))
        assertEquals(CalendarText.Kind.DELETE, CalendarText.kindOf("Delete the meeting with Sam"))
        assertNull(CalendarText.kindOf("How far is Neptune?"))
        assertNull(CalendarText.kindOf("Remind me to call Mom"))

        assertEquals(
            CalendarText.AddRequest("Dentist", "2026-12-02", "09:30", 45, "Samsung", null, true),
            CalendarText.addRequest("```json\n{\"title\":\"Dentist\",\"date\":\"2026-12-02\",\"time\":\"09:30\",\"minutes\":45,\"calendar\":\"Samsung\",\"location\":null}\n```"),
        )
        assertNull(CalendarText.addRequest("{\"title\":\"Dentist\"}")) // no date
        assertEquals("2026-12-02" to "2026-12-04", CalendarText.range("{\"from\":\"2026-12-02\",\"to\":\"2026-12-04\"}"))
        assertEquals("2026-12-02" to "2026-12-02", CalendarText.range("{\"from\":\"2026-12-02\"}"))
        assertEquals(CalendarText.Pick(7, null, "2026-12-03", "10:00", null), CalendarText.pick("{\"id\":7,\"title\":null,\"date\":\"2026-12-03\",\"time\":\"10:00\",\"minutes\":null}"))
        assertNull(CalendarText.pick("{\"id\":null}"))
    }
}
