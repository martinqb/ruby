package com.martin.ruby.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionClassifierTest {

    @Test
    fun phoneOnTable_isStill() {
        assertEquals(Motion.STILL, MotionClassifier.classify(variation = 0.03f, stepsPerSecond = 0f))
    }

    @Test
    fun regularSteps_isWalking() {
        assertEquals(Motion.WALKING, MotionClassifier.classify(variation = 2.5f, stepsPerSecond = 1.8f))
    }

    @Test
    fun stepsWin_evenWithLowVariation() {
        assertEquals(Motion.WALKING, MotionClassifier.classify(variation = 0.1f, stepsPerSecond = 2.0f))
    }

    @Test
    fun steadyVibrationNoSteps_isVehicle() {
        assertEquals(Motion.IN_VEHICLE, MotionClassifier.classify(variation = 0.6f, stepsPerSecond = 0f))
    }

    @Test
    fun noStepDetector_steadyVibration_isVehicle() {
        assertEquals(Motion.IN_VEHICLE, MotionClassifier.classify(variation = 0.6f, stepsPerSecond = null))
    }

    @Test
    fun bigShakesNoSteps_isUnknown() {
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(variation = 4f, stepsPerSecond = 0f))
    }

    @Test
    fun runningPace_isNotWalking() {
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(variation = 6f, stepsPerSecond = 3.5f))
    }

    @Test
    fun variation_ofConstantReadings_isZero() {
        assertEquals(0f, MotionClassifier.variation(List(25) { 9.81f }), 0.0001f)
    }

    @Test
    fun variation_isStandardDeviation() {
        // Mean 10, every sample 1 away from it, so the standard deviation is 1.
        assertEquals(1f, MotionClassifier.variation(listOf(9f, 11f, 9f, 11f)), 0.0001f)
    }

    @Test
    fun variation_ofTooFewSamples_isZero() {
        assertEquals(0f, MotionClassifier.variation(listOf(9.81f)), 0.0001f)
    }
}
