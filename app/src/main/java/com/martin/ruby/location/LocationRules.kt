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

/**
 * Decides when GPS shows you've stopped for good: readings under [stopMph] for [holdMillis]
 * (the "off-timer"). Used to end "In a vehicle" after you park.
 *
 * Parked indoors, GPS drifts: the position wanders and short, made-up speeds appear. So
 * a faster reading only resets the off-timer when it's believable:
 *  - it's accurate to within [maxAccuracyMeters] (drift usually comes with poor accuracy), and
 *  - it lasts at least [driftMillis] in a row.
 * Anything else is ignored as drift, and the timer keeps running.
 *
 * [log] is told when the timer starts, resets, or ignores drift.
 */
class StopDetector(
    private val stopMph: Float = STOP_MPH,
    private val holdMillis: Long = HOLD_MILLIS,
    private val driftMillis: Long = DRIFT_MILLIS,
    private val maxAccuracyMeters: Float = MAX_ACCURACY_METERS,
    private val log: (String) -> Unit = {},
) {
    private var slowSinceMillis: Long? = null
    private var fastSinceMillis: Long? = null
    private var fastestInBurst = 0f
    private var inaccurateLogged = false

    /**
     * Adds one GPS fix: [mph] is null if it had no speed, [accuracyMeters] null if unknown.
     * Returns true once the speed has stayed under [stopMph] for [holdMillis].
     */
    fun add(mph: Float?, accuracyMeters: Float?, timeMillis: Long): Boolean {
        val inaccurate = accuracyMeters != null && accuracyMeters > maxAccuracyMeters
        val movingMph = mph?.takeIf { it >= stopMph && !inaccurate }

        if (movingMph == null) {
            val slowSince = slowSinceMillis
            if (slowSince == null) {
                slowSinceMillis = timeMillis
                inaccurateLogged = false
                log(
                    "Under %.0f mph: off-timer started; leaving the vehicle in %d min unless moving again."
                        .format(stopMph, holdMillis / 60_000)
                )
            }
            fastSinceMillis?.let { fastSince ->
                log(
                    "Ignored %d s at up to %.0f mph (GPS drift); off-timer still running."
                        .format((timeMillis - fastSince) / 1000, fastestInBurst)
                )
                fastSinceMillis = null
            }
            if (inaccurate && mph != null && mph >= stopMph && !inaccurateLogged) {
                inaccurateLogged = true
                log("Ignoring %.0f mph at ±%.0f m (too inaccurate, probably drift).".format(mph, accuracyMeters))
            }
            return timeMillis - (slowSince ?: timeMillis) >= holdMillis
        }

        val slowSince = slowSinceMillis ?: return false // moving, and no timer to reset
        val fastSince = fastSinceMillis ?: timeMillis.also {
            fastSinceMillis = it
            fastestInBurst = 0f
        }
        fastestInBurst = maxOf(fastestInBurst, movingMph)
        if (timeMillis - fastSince >= driftMillis) {
            log(
                "Moving again (%.0f mph for %d s): off-timer reset after %d s."
                    .format(movingMph, (timeMillis - fastSince) / 1000, (timeMillis - slowSince) / 1000)
            )
            reset()
        }
        return false
    }

    fun reset() {
        slowSinceMillis = null
        fastSinceMillis = null
    }

    companion object {
        /** Under this counts as stopped. Parked GPS drift is mostly below it. */
        const val STOP_MPH = 3f
        const val HOLD_MILLIS = 2 * 60_000L
        /** Faster readings have to last this long to reset the timer. */
        const val DRIFT_MILLIS = 5_000L
        /** Faster readings less accurate than this are ignored as drift. */
        const val MAX_ACCURACY_METERS = 25f
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
