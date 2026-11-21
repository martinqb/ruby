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
