package com.martin.ruby.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class MotionClassifierTest {

    private val noRhythm = Rhythm(0f, null)
    private val walkingRhythm = Rhythm(strength = 0.7f, perSecond = 1.4f)

    // classify

    @Test
    fun phoneOnTable_isStill() {
        assertEquals(Motion.STILL, MotionClassifier.classify(0.03f, 0f, noRhythm))
    }

    @Test
    fun regularSteps_isWalking() {
        assertEquals(Motion.WALKING, MotionClassifier.classify(2.5f, 1.8f, noRhythm))
    }

    @Test
    fun slowSteps_isWalking() {
        assertEquals(Motion.WALKING, MotionClassifier.classify(0.8f, 1.1f, noRhythm))
    }

    @Test
    fun slowWalking_stepDetectorSilent_isWalkingFromRhythm() {
        // The bug: slow walking, low shaking, step detector hasn't reported anything yet.
        assertEquals(Motion.WALKING, MotionClassifier.classify(0.7f, 0f, walkingRhythm))
    }

    @Test
    fun slowWalking_noStepPermission_isWalkingFromRhythm() {
        assertEquals(Motion.WALKING, MotionClassifier.classify(0.7f, null, walkingRhythm))
    }

    @Test
    fun handHeldSlowWalking_weakRhythmGentleShaking_isWalking() {
        assertEquals(Motion.WALKING, MotionClassifier.classify(0.25f, 0f, Rhythm(0.38f, 1.2f)))
    }

    @Test
    fun weakRhythm_isNotVehicle() {
        // Too weak to call walking, but too rhythmic to call a vehicle.
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(0.5f, 0f, Rhythm(0.3f, 1.2f)))
    }

    @Test
    fun rhythmWithTinyShaking_isNotWalking() {
        assertEquals(Motion.STILL, MotionClassifier.classify(0.1f, 0f, walkingRhythm))
    }

    @Test
    fun steadyVibrationNoSteps_isVehicle() {
        assertEquals(Motion.IN_VEHICLE, MotionClassifier.classify(0.6f, 0f, noRhythm))
    }

    @Test
    fun someRhythm_isNotVehicle() {
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(0.6f, 0f, Rhythm(0.4f, 5f)))
    }

    @Test
    fun aFewSteps_isNotVehicle() {
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(0.6f, 0.6f, noRhythm))
    }

    @Test
    fun strongerShakingNoSteps_isUnknown() {
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(1.3f, 0f, noRhythm))
    }

    @Test
    fun runningPace_isNotWalking() {
        assertEquals(Motion.UNKNOWN, MotionClassifier.classify(6f, 3.5f, Rhythm(0.8f, 3.5f)))
    }

    // variation

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

    // rhythm

    private fun bounce(beatsPerSecond: Double, rate: Int = 20, seconds: Int = 5, noise: Double = 0.0): List<Float> {
        val random = Random(42)
        return List(rate * seconds) { i ->
            val t = i.toDouble() / rate
            (9.81 + 0.8 * sin(2 * PI * beatsPerSecond * t) + noise * (random.nextDouble() - 0.5)).toFloat()
        }
    }

    @Test
    fun rhythm_findsSlowWalkingBeat() {
        val rhythm = MotionClassifier.rhythm(bounce(1.3, noise = 0.5), samplesPerSecond = 20f)
        assertTrue("strength ${rhythm.strength}", rhythm.strength >= MotionClassifier.WALKING_MIN_RHYTHM)
        assertNotNull(rhythm.perSecond)
        assertEquals(1.3f, rhythm.perSecond!!, 0.15f)
    }

    @Test
    fun rhythm_findsNormalWalkingBeat() {
        val rhythm = MotionClassifier.rhythm(bounce(2.0), samplesPerSecond = 20f)
        assertTrue(rhythm.strength >= MotionClassifier.WALKING_MIN_RHYTHM)
        assertEquals(2.0f, rhythm.perSecond!!, 0.2f)
    }

    @Test
    fun rhythm_findsHandHeldWalking_throughJitterAndDrift() {
        // 10 s of slow walking with the phone in a hand: a weak 1.2/s bounce, hand jitter
        // bigger than the bounce, and the arm slowly rising and falling.
        val random = Random(11)
        val samples = List(200) { i ->
            val t = i / 20.0
            (9.81 + 0.3 * sin(2 * PI * 1.2 * t) + 0.5 * sin(2 * PI * 0.2 * t) +
                1.0 * (random.nextDouble() - 0.5)).toFloat()
        }
        val rhythm = MotionClassifier.rhythm(samples, samplesPerSecond = 20f)
        assertTrue("strength ${rhythm.strength}", rhythm.strength >= MotionClassifier.WALKING_MIN_RHYTHM)
        assertEquals(1.2f, rhythm.perSecond!!, 0.15f)
    }

    @Test
    fun rhythm_ofRandomVibration_isWeak() {
        // 10 s of car-like vibration should look rhythm-free, whatever the random seed.
        for (seed in 1..20) {
            val random = Random(seed)
            val roadNoise = List(200) { (9.81 + 0.6 * (random.nextDouble() - 0.5)).toFloat() }
            val rhythm = MotionClassifier.rhythm(roadNoise, samplesPerSecond = 20f)
            assertTrue("seed $seed strength ${rhythm.strength}", rhythm.strength < MotionClassifier.VEHICLE_MAX_RHYTHM)
        }
    }

    @Test
    fun rhythm_ofConstantReadings_isZero() {
        assertEquals(0f, MotionClassifier.rhythm(List(100) { 9.81f }, 20f).strength, 0.0001f)
    }

    // smoothing

    @Test
    fun smoother_needsThreeVehicleWindowsInARow() {
        val smoother = MotionSmoother()
        assertEquals(Motion.UNKNOWN, smoother.smooth(Motion.IN_VEHICLE))
        assertEquals(Motion.UNKNOWN, smoother.smooth(Motion.IN_VEHICLE))
        assertEquals(Motion.IN_VEHICLE, smoother.smooth(Motion.IN_VEHICLE))
    }

    @Test
    fun smoother_walkingResetsTheVehicleCount() {
        val smoother = MotionSmoother()
        smoother.smooth(Motion.IN_VEHICLE)
        smoother.smooth(Motion.IN_VEHICLE)
        assertEquals(Motion.WALKING, smoother.smooth(Motion.WALKING))
        assertEquals(Motion.WALKING, smoother.smooth(Motion.IN_VEHICLE)) // walking grace
        assertEquals(Motion.WALKING, smoother.smooth(Motion.IN_VEHICLE))
        assertEquals(Motion.IN_VEHICLE, smoother.smooth(Motion.IN_VEHICLE)) // 3 in a row since walking
    }

    @Test
    fun smoother_walkingSticksForTwoWindows() {
        val smoother = MotionSmoother()
        smoother.smooth(Motion.WALKING)
        assertEquals(Motion.WALKING, smoother.smooth(Motion.UNKNOWN)) // phone out of the pocket
        assertEquals(Motion.WALKING, smoother.smooth(Motion.UNKNOWN))
        assertEquals(Motion.UNKNOWN, smoother.smooth(Motion.UNKNOWN)) // 15 s later: really moving
    }

    @Test
    fun smoother_walkingAgain_restartsTheGrace() {
        val smoother = MotionSmoother()
        smoother.smooth(Motion.WALKING)
        smoother.smooth(Motion.STILL)
        smoother.smooth(Motion.WALKING)
        assertEquals(Motion.WALKING, smoother.smooth(Motion.STILL))
        assertEquals(Motion.WALKING, smoother.smooth(Motion.STILL))
        assertEquals(Motion.STILL, smoother.smooth(Motion.STILL))
    }
}
