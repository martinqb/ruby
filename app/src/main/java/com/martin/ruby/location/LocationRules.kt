package com.martin.ruby.location

import com.martin.ruby.data.Place
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Speeds come from GPS in meters per second. */
const val METERS_PER_SECOND_TO_MPH = 2.23694f

/**
 * Decides when GPS speed confirms you're in a vehicle: the speed has to stay at or over
 * 15 mph for 15 seconds. One fast reading (a GPS glitch, a jog) isn't enough.
 */
class SpeedConfirmer(
    private val minMph: Float = MIN_MPH,
    private val holdMillis: Long = HOLD_MILLIS,
) {
    private var fastSinceMillis: Long? = null

    /** Adds one GPS reading. Returns true once the speed has been held long enough. */
    fun add(speedMetersPerSecond: Float, timeMillis: Long): Boolean {
        if (speedMetersPerSecond * METERS_PER_SECOND_TO_MPH < minMph) {
            fastSinceMillis = null
            return false
        }
        val since = fastSinceMillis ?: timeMillis.also { fastSinceMillis = it }
        return timeMillis - since >= holdMillis
    }

    fun reset() {
        fastSinceMillis = null
    }

    companion object {
        const val MIN_MPH = 15f
        const val HOLD_MILLIS = 15_000L
    }
}

/** Finds which saved place you're at. This is simple geofencing, done on the phone. */
object PlaceMatcher {

    /** How close you need to be to a saved place to count as being there. */
    const val RADIUS_METERS = 100.0

    /** Ignore fixes less accurate than this; they could put you in the wrong place. */
    const val MAX_ACCURACY_METERS = 100f

    /** The nearest saved place within [RADIUS_METERS], or null if you're not at one. */
    fun placeAt(places: List<Place>, latitude: Double, longitude: Double): Place? =
        places
            .map { it to distanceMeters(latitude, longitude, it.latitude, it.longitude) }
            .filter { (_, distance) -> distance <= RADIUS_METERS }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    /** Distance along the Earth's surface (the haversine formula). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(a))
    }
}
