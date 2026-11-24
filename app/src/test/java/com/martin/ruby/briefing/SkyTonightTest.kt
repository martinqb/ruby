package com.martin.ruby.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Step 5: tonight's sky, worked out on the phone with Astronomy Engine. */
class SkyTonightTest {

    companion object {
        // Kansas City, as a test place.
        const val LAT = 39.10
        const val LON = -94.58
        val ZONE: ZoneId = ZoneId.of("America/Chicago")

        fun sampleSky() = SkyTonight(
            darkFrom = LocalDateTime.of(2026, 11, 24, 17, 58),
            darkUntil = LocalDateTime.of(2026, 11, 25, 6, 2),
            moonPhase = "waxing gibbous",
            moonLit = 78,
            moonrise = LocalDateTime.of(2026, 11, 24, 14, 10),
            moonset = LocalDateTime.of(2026, 11, 25, 3, 20),
            clouds = (18..23).map { LocalDateTime.of(2026, 11, 24, it, 0) to if (it == 18) 60 else 10 },
            planets = listOf(PlanetView("Jupiter", LocalDateTime.of(2026, 11, 24, 23, 40), 52, "east", -2.6)),
        )
    }

    @Test
    fun moonPhases_atKnownDates() {
        // Full moon: 23 April 2024. New moon (the solar eclipse): 8 April 2024.
        val full = SkyTonight.compute(LAT, LON, LocalDate.of(2024, 4, 23), ZONE, null)
        assertEquals("full moon", full.moonPhase)
        assertTrue("${full.moonLit}% lit", full.moonLit >= 95)
        val new = SkyTonight.compute(LAT, LON, LocalDate.of(2024, 4, 8), ZONE, null)
        assertTrue(new.moonPhase, new.moonPhase in setOf("new moon", "waxing crescent"))
        assertTrue("${new.moonLit}% lit", new.moonLit <= 5)
    }

    @Test
    fun darkness_startsAboutAnHourAfterSunset() {
        // Kansas City, 24 November: sunset about 4:59 PM, so it's properly dark around 6.
        val sky = SkyTonight.compute(LAT, LON, LocalDate.of(2026, 11, 24), ZONE, null)
        assertTrue("dark from ${sky.darkFrom}", sky.darkFrom.isAfter(LocalDateTime.of(2026, 11, 24, 17, 35)))
        assertTrue("dark from ${sky.darkFrom}", sky.darkFrom.isBefore(LocalDateTime.of(2026, 11, 24, 18, 20)))
        assertTrue("light again ${sky.darkUntil}", sky.darkUntil.toLocalDate() == LocalDate.of(2026, 11, 25) && sky.darkUntil.hour in 5..6)
    }

    @Test
    fun theJanuary2025PlanetParade_isFound() {
        // 21 January 2025: Venus, Jupiter, Mars and Saturn all up in the evening.
        val sky = SkyTonight.compute(LAT, LON, LocalDate.of(2025, 1, 21), ZONE, null)
        val names = sky.planets.map { it.name }
        assertTrue(names.toString(), names.containsAll(listOf("Venus", "Jupiter", "Mars", "Saturn")))
        // Brightest first: Venus.
        assertEquals("Venus", sky.planets.first().name)
        assertTrue(sky.planets.first { it.name == "Venus" }.direction in setOf("southwest", "west", "south"))
        assertTrue(sky.planets.all { it.altitude >= 10 })
        // Uranus and Neptune are telescope targets, not "easy to see".
        assertTrue(sky.planets.filter { it.name in setOf("Uranus", "Neptune") }.none { it.nakedEye })
        assertTrue(sky.planets.filter { it.name in setOf("Venus", "Jupiter", "Mars", "Saturn") }.all { it.nakedEye })
    }

    @Test
    fun clearSkyChance_fromTheEveningsClouds() {
        val forecast = ForecastTest.forecast() // 95% cloud 3 to 7 PM, 15% from 8 PM
        val sky = SkyTonight.compute(LAT, LON, LocalDate.of(2026, 11, 24), ZONE, forecast)
        assertTrue(sky.clouds.isNotEmpty())
        assertEquals("fair", sky.clearChance) // cloudy at first, clear from 8
        assertEquals(LocalDateTime.of(2026, 11, 24, 20, 0), sky.clearestStretch?.first)

        assertEquals("good", sampleSky().clearChance)
        assertEquals("poor", sampleSky().copy(clouds = sampleSky().clouds.map { it.first to 90 }).clearChance)
        assertNull(sampleSky().copy(clouds = emptyList()).clearChance)
    }

    @Test
    fun phaseNames_andDirections() {
        assertEquals("new moon", SkyTonight.phaseName(355.0))
        assertEquals("first quarter", SkyTonight.phaseName(90.0))
        assertEquals("waxing gibbous", SkyTonight.phaseName(135.0))
        assertEquals("full moon", SkyTonight.phaseName(180.0))
        assertEquals("last quarter", SkyTonight.phaseName(270.0))
        assertEquals("waning crescent", SkyTonight.phaseName(300.0))
        assertEquals("north", SkyTonight.compass(350.0))
        assertEquals("southwest", SkyTonight.compass(225.0))
    }

    @Test
    fun theSkyFacts_forClaude() {
        val text = BriefingText.skyFacts(sampleSky())
        assertTrue(text, text.contains("Clear-sky chance this evening: good."))
        assertTrue(text, text.contains("Moon: waxing gibbous, 78% lit, rises 2:10 PM, sets 3:20 AM."))
        assertTrue(text, text.contains("- Jupiter: highest around 11:40 PM, 52° up in the east, easy to see."))
    }
}
