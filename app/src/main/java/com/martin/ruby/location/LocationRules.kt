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
 * 15 mph for 10 seconds. One fast reading (a GPS glitch, a jog) isn't enough.
 *
 * Short dips under 15 mph (a noisy reading, slowing for a bend) are tolerated: the count
 * only starts over once the speed has stayed under 15 mph for more than [graceMillis].
 * The time spent in a tolerated dip still counts toward the 10 seconds.
 *
 * [log] is told when a dip starts, is tolerated, or resets the count.
 */
class SpeedConfirmer(
    private val minMph: Float = MIN_MPH,
    private val holdMillis: Long = HOLD_MILLIS,
    private val graceMillis: Long = DIP_GRACE_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private var fastSinceMillis: Long? = null
    private var slowSinceMillis: Long? = null

    /** Adds one GPS reading. Returns true once the speed has been held long enough. */
    fun add(speedMetersPerSecond: Float, timeMillis: Long): Boolean {
        val mph = speedMetersPerSecond * METERS_PER_SECOND_TO_MPH
        val since = fastSinceMillis

        if (mph < minMph) {
            if (since == null) return false   // not counting, nothing to tolerate
            val slowSince = slowSinceMillis ?: timeMillis.also {
                slowSinceMillis = it
                log("Speed dipped to %.0f mph; tolerating up to %d s.".format(mph, graceMillis / 1000))
            }
            if (timeMillis - slowSince > graceMillis) {
                log("Under %.0f mph for over %d s: vehicle count reset.".format(minMph, graceMillis / 1000))
                reset()
            }
            return false
        }

        slowSinceMillis?.let { slowSince ->
            log("Dip tolerated (%.0f s under %.0f mph); still counting.".format((timeMillis - slowSince) / 1000f, minMph))
            slowSinceMillis = null
        }
        val start = since ?: timeMillis.also { fastSinceMillis = it }
        return timeMillis - start >= holdMillis
    }

    fun reset() {
        fastSinceMillis = null
        slowSinceMillis = null
    }

    companion object {
        const val MIN_MPH = 15f
        const val HOLD_MILLIS = 10_000L

        /** A dip under [MIN_MPH] has to last longer than this to start the count over. */
        const val DIP_GRACE_MILLIS = 3_000L
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
