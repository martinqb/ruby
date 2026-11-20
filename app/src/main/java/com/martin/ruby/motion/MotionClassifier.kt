package com.martin.ruby.motion

import kotlin.math.sqrt

/** What the phone's motion suggests you're doing. */
enum class Motion(val label: String) {
    STILL("Still"),
    WALKING("Walking"),
    IN_VEHICLE("Possibly in a vehicle"),
    UNKNOWN("Moving"), // e.g. the phone is in your hand, or you're running
}

/** The result of one 5-second window, plus the numbers behind it (handy for tuning). */
data class MotionReading(
    val motion: Motion,
    /** How much the acceleration varied, in m/s² (standard deviation). */
    val variation: Float,
    /** Steps per second, or null if the step detector isn't available or allowed. */
    val stepsPerSecond: Float?,
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

    /** A car's vibration is steady and fairly low. Walking and handling the phone go above this. */
    const val VEHICLE_MAX_VARIATION = 1.5f

    /** Walking pace. The plan says 1.5 to 2.5 steps a second; the edges are a bit wider
     *  because the step detector reports steps a little late, so windows can be uneven. */
    const val WALKING_MIN_STEPS_PER_SECOND = 1.2f
    const val WALKING_MAX_STEPS_PER_SECOND = 3.0f

    /** In a vehicle there should be (almost) no step rhythm. */
    const val VEHICLE_MAX_STEPS_PER_SECOND = 0.5f

    fun classify(variation: Float, stepsPerSecond: Float?): Motion {
        val steps = stepsPerSecond ?: 0f
        return when {
            steps in WALKING_MIN_STEPS_PER_SECOND..WALKING_MAX_STEPS_PER_SECOND -> Motion.WALKING
            variation < STILL_MAX_VARIATION -> Motion.STILL
            variation <= VEHICLE_MAX_VARIATION && steps < VEHICLE_MAX_STEPS_PER_SECOND -> Motion.IN_VEHICLE
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
}
