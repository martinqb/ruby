package com.martin.ruby.decision

import com.martin.ruby.decision.ContextState.Kind
import com.martin.ruby.motion.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextRulesTest {

    private fun decide(inputs: ContextInputs) = ContextRules.decide(inputs).state.kind

    // ContextRules: the order of confidence

    @Test
    fun carBluetooth_beatsEverything() {
        val inputs = ContextInputs(carConnected = true, inVehicle = true, place = "Home", motion = Motion.WALKING)
        assertEquals(Kind.DRIVING, decide(inputs))
    }

    @Test
    fun gpsVehicle_beatsPlaceAndMotion() {
        // Driving past a saved place shouldn't say "At Gym".
        val inputs = ContextInputs(inVehicle = true, place = "Gym", motion = Motion.STILL)
        assertEquals(Kind.IN_VEHICLE, decide(inputs))
    }

    @Test
    fun place_beatsMotion() {
        val decision = ContextRules.decide(ContextInputs(place = "Home", motion = Motion.WALKING))
        assertEquals(ContextState(Kind.AT_PLACE, "Home"), decision.state)
        assertEquals("At Home", decision.state.label)
    }

    @Test
    fun otherwise_motionDecides() {
        assertEquals(Kind.WALKING, decide(ContextInputs(motion = Motion.WALKING)))
        assertEquals(Kind.STILL, decide(ContextInputs(motion = Motion.STILL)))
        assertEquals(Kind.POSSIBLY_IN_VEHICLE, decide(ContextInputs(motion = Motion.IN_VEHICLE)))
        assertEquals(Kind.MOVING, decide(ContextInputs(motion = Motion.UNKNOWN)))
        assertEquals(Kind.UNKNOWN, decide(ContextInputs(motion = null)))
    }

    @Test
    fun pausedSensors_meanStill() {
        assertEquals(Kind.STILL, decide(ContextInputs(motion = Motion.WALKING, motionPaused = true)))
    }

    // ContextSmoother: only change after 30 s

    private val walking = ContextState(Kind.WALKING)
    private val still = ContextState(Kind.STILL)
    private val inVehicle = ContextState(Kind.IN_VEHICLE)
    private val driving = ContextState(Kind.DRIVING)

    @Test
    fun firstState_showsStraightAway() {
        val smoother = ContextSmoother()
        assertEquals(walking, smoother.add(walking, 0L))
    }

    @Test
    fun newState_switchesAfterHolding30Seconds() {
        val smoother = ContextSmoother()
        smoother.add(inVehicle, 0L)
        assertNull(smoother.add(still, 1_000L))
        assertNull(smoother.add(still, 30_999L))
        assertEquals(still, smoother.pending)
        assertEquals(still, smoother.add(still, 31_000L))
        assertNull(smoother.pending)
    }

    @Test
    fun shortBlip_doesNotSwitch() {
        val smoother = ContextSmoother()
        smoother.add(walking, 0L)
        assertNull(smoother.add(still, 10_000L))   // paused at a crossing
        assertNull(smoother.add(walking, 20_000L)) // walking again: blip forgotten
        assertNull(smoother.pending)
        assertNull(smoother.add(still, 45_000L))   // a new pause starts its own timer
        assertEquals(walking, smoother.current)
    }

    @Test
    fun flickeringCandidates_restartTheTimer() {
        val smoother = ContextSmoother()
        smoother.add(inVehicle, 0L)
        smoother.add(still, 0L)
        smoother.add(walking, 20_000L)
        assertNull(smoother.add(walking, 39_000L)) // only 19 s of walking
        assertEquals(walking, smoother.add(walking, 40_000L))
    }

    @Test
    fun walking_switchesAfter20Seconds_othersStillWait30() {
        val smoother = ContextSmoother()
        smoother.add(still, 0L)
        assertNull(smoother.add(walking, 1_000L))
        assertNull(smoother.add(walking, 20_999L))
        assertEquals(walking, smoother.add(walking, 21_000L))

        assertNull(smoother.add(still, 30_000L))
        assertNull(smoother.add(still, 59_999L))   // Still keeps the 30 s hold
        assertEquals(still, smoother.add(still, 60_000L))
    }

    @Test
    fun driving_switchesStraightAway_butLeavingWaits() {
        val smoother = ContextSmoother()
        smoother.add(walking, 0L)
        assertEquals(driving, smoother.add(driving, 5_000L))
        assertNull(smoother.add(still, 10_000L)) // a Bluetooth drop
        assertEquals(driving, smoother.current)
        assertEquals(still, smoother.add(still, 40_000L))
    }

    @Test
    fun differentPlaces_areDifferentStates() {
        val smoother = ContextSmoother()
        smoother.add(ContextState(Kind.AT_PLACE, "Home"), 0L)
        assertNull(smoother.add(ContextState(Kind.AT_PLACE, "Gym"), 1_000L))
        assertEquals(ContextState(Kind.AT_PLACE, "Gym"), smoother.add(ContextState(Kind.AT_PLACE, "Gym"), 31_000L))
    }
}
