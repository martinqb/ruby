package com.martin.ruby.location

import com.martin.ruby.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationRulesTest {

    private fun mph(mph: Float) = mph / METERS_PER_SECOND_TO_MPH

    // SpeedConfirmer

    @Test
    fun speed_heldFor10Seconds_confirms() {
        val confirmer = SpeedConfirmer()
        for (second in 0 until 10) assertFalse(confirmer.add(mph(25f), second * 1000L))
        assertTrue(confirmer.add(mph(25f), 10_000L))
    }

    @Test
    fun speed_shortDipBelow15Mph_isTolerated() {
        val confirmer = SpeedConfirmer()
        confirmer.add(mph(30f), 0L)
        confirmer.add(mph(30f), 5_000L)
        assertFalse(confirmer.add(mph(10f), 6_000L)) // one noisy reading
        assertFalse(confirmer.add(mph(12f), 7_000L))
        assertFalse(confirmer.add(mph(12f), 9_000L)) // 3 s under: still within the grace period
        assertTrue(confirmer.add(mph(30f), 10_000L))
    }

    @Test
    fun speed_dipLongerThan3Seconds_startsOver() {
        val confirmer = SpeedConfirmer()
        confirmer.add(mph(30f), 0L)
        confirmer.add(mph(30f), 5_000L)
        for (t in 6_000L..10_000L step 1_000L) assertFalse(confirmer.add(mph(10f), t)) // stopped at a junction
        assertFalse(confirmer.add(mph(30f), 11_000L))
        assertFalse(confirmer.add(mph(30f), 20_000L))
        assertTrue(confirmer.add(mph(30f), 21_000L))
    }

    @Test
    fun speed_dips_areLogged() {
        val lines = mutableListOf<String>()
        val confirmer = SpeedConfirmer(log = { lines += it })
        confirmer.add(mph(30f), 0L)
        confirmer.add(mph(10f), 1_000L)
        confirmer.add(mph(30f), 2_000L)
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("Dip tolerated"))
    }

    @Test
    fun speed_walkingOrJogging_neverConfirms() {
        val confirmer = SpeedConfirmer()
        for (second in 0..120) assertFalse(confirmer.add(mph(8f), second * 1000L))
    }

    @Test
    fun speed_reset_startsOver() {
        val confirmer = SpeedConfirmer()
        confirmer.add(mph(30f), 0L)
        confirmer.reset()
        assertFalse(confirmer.add(mph(30f), 10_000L))
    }

    // StopDetector: ending "In a vehicle" after parking

    @Test
    fun stop_2MinutesUnder3Mph_isStopped() {
        val stop = StopDetector()
        for (second in 0 until 120) assertFalse(stop.add(0.5f, 8f, second * 1000L))
        assertTrue(stop.add(0f, 8f, 120_000L))
    }

    @Test
    fun stop_shortDriftBursts_areIgnored() {
        val stop = StopDetector()
        stop.add(0f, 8f, 0L)
        // Parked indoors: GPS makes up 4 mph for 3 seconds now and then.
        for (t in 30_000L..32_000L step 1_000L) assertFalse(stop.add(4f, 10f, t))
        stop.add(1f, 10f, 33_000L)
        for (t in 80_000L..83_000L step 1_000L) stop.add(6f, 12f, t)
        stop.add(0f, 10f, 84_000L)
        assertTrue(stop.add(0f, 10f, 120_000L))
    }

    @Test
    fun stop_inaccurateSpeeds_areIgnored() {
        val stop = StopDetector()
        stop.add(0f, 8f, 0L)
        // ±60 m: drift, however long it lasts.
        for (second in 10..100) assertFalse(stop.add(7f, 60f, second * 1000L))
        assertTrue(stop.add(7f, 60f, 120_000L))
    }

    @Test
    fun stop_drivingOffAgain_resetsTheTimer() {
        val stop = StopDetector()
        stop.add(0f, 8f, 0L)                                     // long red light
        for (t in 60_000L..65_000L step 1_000L) stop.add(20f, 5f, t) // 5 s at 20 mph, accurate
        stop.add(0f, 8f, 70_000L)                                // stopped again: new timer
        assertFalse(stop.add(0f, 8f, 120_000L))
        assertTrue(stop.add(0f, 8f, 190_000L))
    }

    @Test
    fun stop_fixesWithNoSpeed_countAsStopped() {
        val stop = StopDetector()
        stop.add(null, null, 0L)
        assertTrue(stop.add(null, null, 120_000L))
    }

    @Test
    fun stop_logsStartResetAndDrift() {
        val lines = mutableListOf<String>()
        val stop = StopDetector(log = { lines += it })
        stop.add(0f, 8f, 0L)
        stop.add(4f, 8f, 1_000L)
        stop.add(0f, 8f, 2_000L)
        for (t in 3_000L..8_000L step 1_000L) stop.add(20f, 5f, t)
        assertTrue(lines[0].contains("off-timer started"))
        assertTrue(lines[1].contains("GPS drift"))
        assertTrue(lines[2].contains("off-timer reset"))
    }

    // PlaceMatcher

    private val home = Place("Home", 51.5007, -0.1246)
    private val gym = Place("Gym", 51.5033, -0.1196)

    @Test
    fun distance_isAboutRight() {
        // 0.001 degrees of latitude is about 111 meters.
        assertEquals(111.2, PlaceMatcher.distanceMeters(51.0, 0.0, 51.001, 0.0), 0.5)
    }

    @Test
    fun place_within100Meters_matches() {
        // About 55 m north of Home.
        assertEquals(home, PlaceMatcher.placeAt(listOf(home, gym), 51.5012, -0.1246))
    }

    @Test
    fun place_moreThan100MetersAway_doesNotMatch() {
        // About 220 m north of Home, and far from the gym.
        assertNull(PlaceMatcher.placeAt(listOf(home, gym), 51.5027, -0.1260))
    }

    @Test
    fun place_picksTheNearest() {
        val shop = Place("Shop", 51.5010, -0.1246) // 33 m from Home
        assertEquals(shop, PlaceMatcher.placeAt(listOf(home, shop), 51.5011, -0.1246))
    }

    @Test
    fun place_noSavedPlaces_isNull() {
        assertNull(PlaceMatcher.placeAt(emptyList(), 51.5007, -0.1246))
    }
}
