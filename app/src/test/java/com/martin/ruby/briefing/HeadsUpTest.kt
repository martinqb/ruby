package com.martin.ruby.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Step 4: practical notes for the drive and the day. */
class HeadsUpTest {

    private val zone = ZoneId.of("America/Chicago")
    private val now = LocalDateTime.of(2026, 11, 24, 7, 42)

    private fun notes(json: String, events: List<CalendarEvent>? = null) =
        HeadsUp.notes(ForecastTest.forecast(json), events, now, zone)

    private val nice = ForecastTest.json(
        temp = { 60.0 }, rain = { 0 }, code = { 1 }, precipitation = { 0.0 },
    )

    @Test
    fun aNiceDay_needsNoHeadsUp() {
        assertEquals(emptyList<String>(), notes(nice))
    }

    @Test
    fun rainLater_saysWhen() {
        val n = notes(ForecastTest.json(temp = { 50.0 }))
        assertEquals(listOf("Rain likely from about 3:00 PM (70% chance): an umbrella would help."), n)
    }

    @Test
    fun rainDuringTheDrive() {
        val n = notes(ForecastTest.json(temp = { 50.0 }, rain = { h -> if (h in 7..8) 80 else 0 }, code = { h -> if (h in 7..8) 61 else 2 }))
        assertEquals(listOf("Rain likely during the drive (80% chance)."), n)
    }

    @Test
    fun nearFreezingAfterOvernightRain_meansSlickRoads() {
        val n = notes(
            ForecastTest.json(
                temp = { h -> if (h < 10) 32.0 else 40.0 },
                rain = { 0 }, code = { 3 },
                precipitation = { h -> if (h in 1..4) 0.8 else 0.0 },
            )
        )
        assertEquals("Near freezing (32°F) after overnight rain or snow: watch for slick roads and black ice.", n.first())
    }

    @Test
    fun snowAndFog_andWind_mostImportantFirst_atMostThree() {
        val n = notes(
            ForecastTest.json(
                temp = { 28.0 }, rain = { h -> if (h in 7..8) 60 else 0 },
                code = { h -> if (h in 7..8) 73 else 45 },
                visibility = { h -> if (h == 8) 400 else 5000 },
                gusts = { 45 },
            ),
            events = listOf(
                CalendarEvent("Stand-up", now.withHour(8).withMinute(30).atZone(zone).toInstant().toEpochMilli(), 0, false),
            ),
        )
        assertEquals(HeadsUp.MAX_NOTES, n.size)
        assertEquals("Snow during the drive: roads may be slick.", n[0])
        assertEquals("Fog likely during the drive, with visibility down to about 400 meters.", n[1])
        assertTrue(n[2], n[2].startsWith("Strong gusts up to 45 mph"))
    }

    @Test
    fun anEarlyFirstEvent() {
        val event = CalendarEvent("Stand-up", now.withHour(8).withMinute(30).atZone(zone).toInstant().toEpochMilli(), 0, false)
        assertEquals(listOf("First event soon: Stand-up at 8:30 AM."), notes(nice, listOf(event)))
        // Later in the day, or all day: not a heads-up.
        val later = event.copy(startMillis = now.withHour(14).atZone(zone).toInstant().toEpochMilli())
        assertEquals(emptyList<String>(), notes(nice, listOf(later, event.copy(allDay = true))))
    }

    @Test
    fun noForecast_stillChecksTheCalendar() {
        val event = CalendarEvent("Stand-up", now.withHour(8).withMinute(0).atZone(zone).toInstant().toEpochMilli(), 0, false)
        assertEquals(listOf("First event soon: Stand-up at 8:00 AM."), HeadsUp.notes(null, listOf(event), now, zone))
    }
}
