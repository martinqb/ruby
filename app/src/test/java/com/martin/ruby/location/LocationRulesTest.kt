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
    fun speed_dippingBelow15Mph_startsOver() {
        val confirmer = SpeedConfirmer()
        confirmer.add(mph(30f), 0L)
        confirmer.add(mph(30f), 8_000L)
        assertFalse(confirmer.add(mph(10f), 9_000L)) // slowed down at a junction
        assertFalse(confirmer.add(mph(30f), 10_000L))
        assertFalse(confirmer.add(mph(30f), 19_000L))
        assertTrue(confirmer.add(mph(30f), 20_000L))
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
