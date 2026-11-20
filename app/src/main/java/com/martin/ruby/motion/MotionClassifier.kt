package com.martin.ruby.motion

import kotlin.math.sqrt

/** What the phone's motion suggests you're doing. */
enum class Motion(val label: String) {
    STILL("Still"),
    WALKING("Walking"),
    IN_VEHICLE("Possibly in a vehicle"),
    UNKNOWN("Moving"), // e.g. the phone is in your hand, or you're running
}

/** How strongly the shaking repeats at a steady beat, like footsteps. */
data class Rhythm(
    /** 0 = no repeating pattern, 1 = perfectly regular. */
    val strength: Float,
    /** How many beats a second, or null if there's no clear beat. */
    val perSecond: Float?,
)

/** The result of one 5-second window, plus the numbers behind it (handy for tuning). */
data class MotionReading(
    val motion: Motion,
    /** How much the acceleration varied, in m/s² (standard deviation). */
    val variation: Float,
    /** Steps per second, or null if the step detector isn't available or allowed. */
    val stepsPerSecond: Float?,
    val rhythm: Rhythm = Rhythm(0f, null),
    /** True while sensors are off, waiting for the phone to start moving again. */
    val paused: Boolean = false,
)

/**
 * Simple rules that turn one window of sensor data into a [Motion].
 * Kept separate from the sensor code so it's easy to test and tune.
 */
object MotionClassifier {

    /** Below this, the phone is basically not moving (lying on a table, in a mounted holder). */
    const val STILL_MAX_VARIATION = 0.15f

    /** Walking pace from the step detector. Slow walking is around 1.3 steps a second, and
     *  the step detector often reports late or misses a few, so the bottom is set low. */
    const val WALKING_MIN_STEPS_PER_SECOND = 1.0f
    const val WALKING_MAX_STEPS_PER_SECOND = 3.0f

    /** Walking from the accelerometer alone: a clear beat at a walking pace (or one beat per
     *  stride, which is half that), with enough shaking that it isn't just noise. */
    const val WALKING_MIN_RHYTHM = 0.5f
    const val WALKING_MIN_BEATS_PER_SECOND = 0.6f
    const val WALKING_MAX_BEATS_PER_SECOND = 3.0f
    const val WALKING_MIN_VARIATION = 0.3f

    /** A vehicle needs steady, low shaking with no footstep rhythm and (almost) no steps. */
    const val VEHICLE_MAX_VARIATION = 1.0f
    const val VEHICLE_MAX_RHYTHM = 0.3f
    const val VEHICLE_MAX_STEPS_PER_SECOND = 0.3f

    fun classify(variation: Float, stepsPerSecond: Float?, rhythm: Rhythm): Motion {
        val steps = stepsPerSecond ?: 0f
        val walkingBySteps = steps in WALKING_MIN_STEPS_PER_SECOND..WALKING_MAX_STEPS_PER_SECOND
        val beats = rhythm.perSecond
        val walkingByRhythm = rhythm.strength >= WALKING_MIN_RHYTHM &&
            beats != null && beats in WALKING_MIN_BEATS_PER_SECOND..WALKING_MAX_BEATS_PER_SECOND &&
            variation >= WALKING_MIN_VARIATION

        return when {
            walkingBySteps || walkingByRhythm -> Motion.WALKING
            variation < STILL_MAX_VARIATION -> Motion.STILL
            variation <= VEHICLE_MAX_VARIATION &&
                rhythm.strength < VEHICLE_MAX_RHYTHM &&
                steps < VEHICLE_MAX_STEPS_PER_SECOND -> Motion.IN_VEHICLE
            else -> Motion.UNKNOWN
        }
    }

    /** Standard deviation of the acceleration magnitudes. Gravity (about 9.8) is the same in
     *  every sample, so it cancels out and only the shaking is left. */
    fun variation(magnitudes: List<Float>): Float {
        if (magnitudes.size < 2) return 0f
        val mean = magnitudes.average()
        val squaredDiffs = magnitudes.sumOf { (it - mean) * (it - mean) }
        return sqrt(squaredDiffs / magnitudes.size).toFloat()
    }

    /**
     * Looks for a steady beat in the shaking, between 0.6 and 3 beats a second.
     *
     * It compares the signal with a copy of itself shifted a little in time
     * (autocorrelation). If the shaking repeats every half second, the two line up when
     * shifted by half a second. Footsteps repeat like that; a car's vibration doesn't.
     */
    fun rhythm(magnitudes: List<Float>, samplesPerSecond: Float): Rhythm {
        val n = magnitudes.size
        if (n < 10 || samplesPerSecond <= 0f) return Rhythm(0f, null)

        val mean = magnitudes.average()
        val centered = DoubleArray(n) { magnitudes[it] - mean }
        val variance = centered.sumOf { it * it } / n
        if (variance == 0.0) return Rhythm(0f, null)

        // A shift of `lag` samples matches a beat of samplesPerSecond / lag per second.
        val minLag = maxOf(2, (samplesPerSecond / WALKING_MAX_BEATS_PER_SECOND).toInt())
        val maxLag = minOf(n / 2, (samplesPerSecond / WALKING_MIN_BEATS_PER_SECOND).toInt() + 1)
        if (minLag >= maxLag) return Rhythm(0f, null)

        fun similarity(lag: Int): Double {
            var sum = 0.0
            for (i in 0 until n - lag) sum += centered[i] * centered[i + lag]
            return (sum / (n - lag)) / variance
        }

        val scores = (minLag - 1..maxLag + 1).associateWith { similarity(it) }
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val score = scores.getValue(lag)
            // Only count real peaks; a slow sway also scores well at short shifts.
            val isPeak = score >= scores.getValue(lag - 1) && score >= scores.getValue(lag + 1)
            if (isPeak && score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag < 0) return Rhythm(0f, null)
        return Rhythm(
            strength = bestScore.coerceIn(0.0, 1.0).toFloat(),
            perSecond = samplesPerSecond / bestLag,
        )
    }
}

/**
 * Makes the result steadier between windows. Walking shows up straight away, but
 * "Possibly in a vehicle" has to hold for [VEHICLE_WINDOWS_NEEDED] windows in a row,
 * so a few smooth seconds (standing in a queue, a short ride in an elevator) don't count.
 */
class MotionSmoother {
    private var vehicleWindows = 0

    fun smooth(raw: Motion): Motion {
        if (raw != Motion.IN_VEHICLE) {
            vehicleWindows = 0
            return raw
        }
        vehicleWindows++
        return if (vehicleWindows >= VEHICLE_WINDOWS_NEEDED) Motion.IN_VEHICLE else Motion.UNKNOWN
    }

    fun reset() {
        vehicleWindows = 0
    }

    companion object {
        const val VEHICLE_WINDOWS_NEEDED = 3 // 3 x 5 s = 15 seconds
    }
}
