package com.martin.ruby.decision

import com.martin.ruby.motion.Motion

/** What Ruby thinks you're doing right now: the one label the rest of the app uses. */
data class ContextState(val kind: Kind, val place: String? = null) {

    /** In order of confidence, highest first (see [ContextRules.decide]). */
    enum class Kind {
        DRIVING,             // the car's Bluetooth is connected
        IN_VEHICLE,          // GPS speed confirmed a vehicle (some other car, a bus...)
        AT_PLACE,            // within 100 m of a saved place
        WALKING,
        POSSIBLY_IN_VEHICLE, // motion looks like a vehicle, GPS hasn't confirmed it
        MOVING,              // moving, but not clearly walking or riding
        STILL,
        UNKNOWN,             // not enough to go on yet (sensing just started)
    }

    val label: String
        get() = when (kind) {
            Kind.DRIVING -> "Driving"
            Kind.IN_VEHICLE -> "In a vehicle"
            Kind.AT_PLACE -> "At ${place ?: "a saved place"}"
            Kind.WALKING -> "Walking"
            Kind.POSSIBLY_IN_VEHICLE -> "Possibly in a vehicle"
            Kind.MOVING -> "Moving"
            Kind.STILL -> "Still"
            Kind.UNKNOWN -> "Not sure yet"
        }

    companion object {
        val UNKNOWN = ContextState(Kind.UNKNOWN)
    }
}

/** Everything the decision is made from. Each one is worked out elsewhere (Phases 1-4). */
data class ContextInputs(
    /** The chosen car's Bluetooth is connected (Phase 1). */
    val carConnected: Boolean = false,
    /**
     * GPS confirmed a vehicle (Phase 4). This is already smoothed: short speed dips are
     * tolerated and it only ends after 30 s of walking or 2 minutes without movement,
     * so a stoplight doesn't clear it.
     */
    val inVehicle: Boolean = false,
    /** The saved place you're at, or null (Phase 4). */
    val place: String? = null,
    /** The smoothed motion result, or null if motion sensing isn't running (Phase 3). */
    val motion: Motion? = null,
    /** True while the motion sensors are paused because the phone has been still. */
    val motionPaused: Boolean = false,
)

/** A decision and the reason for it, so the log can say why the state changed. */
data class Decision(val state: ContextState, val reason: String)

object ContextRules {

    /** Picks the one current state, taking the most confident signal first. */
    fun decide(inputs: ContextInputs): Decision = when {
        inputs.carConnected ->
            Decision(ContextState(ContextState.Kind.DRIVING), "car Bluetooth connected")
        inputs.inVehicle ->
            Decision(ContextState(ContextState.Kind.IN_VEHICLE), "GPS speed confirmed a vehicle")
        inputs.place != null ->
            Decision(ContextState(ContextState.Kind.AT_PLACE, inputs.place), "within 100 m of ${inputs.place}")
        inputs.motionPaused ->
            Decision(ContextState(ContextState.Kind.STILL), "phone still, sensors paused")
        else -> when (inputs.motion) {
            Motion.WALKING -> Decision(ContextState(ContextState.Kind.WALKING), "motion: walking")
            Motion.STILL -> Decision(ContextState(ContextState.Kind.STILL), "motion: still")
            Motion.IN_VEHICLE -> Decision(ContextState(ContextState.Kind.POSSIBLY_IN_VEHICLE), "motion looks like a vehicle")
            Motion.UNKNOWN -> Decision(ContextState(ContextState.Kind.MOVING), "motion: moving")
            null -> Decision(ContextState.UNKNOWN, "motion sensing not running")
        }
    }
}

/**
 * Remembers the decisions of the last [windowMillis] and picks the strongest: the
 * highest in [ContextState.Kind]'s order of confidence (Driving first, "Not sure yet"
 * last). Used to tag a saved note, so pulling the phone out to type doesn't tag it
 * "Moving" when you were walking a moment ago. A tie goes to the newest.
 */
class RecentStates(private val windowMillis: Long = WINDOW_MILLIS) {

    private val entries = ArrayDeque<Pair<Long, ContextState>>()

    fun add(state: ContextState, timeMillis: Long) {
        entries.addLast(timeMillis to state)
        while (timeMillis - entries.first().first > windowMillis) entries.removeFirst()
    }

    /** The strongest state seen in the last [windowMillis], or null if none. */
    fun strongest(timeMillis: Long): ContextState? = entries
        .filter { (time, _) -> timeMillis - time <= windowMillis }
        .minWithOrNull(compareBy<Pair<Long, ContextState>> { it.second.kind.ordinal }.thenByDescending { it.first })
        ?.second

    fun clear() = entries.clear()

    companion object {
        /** "The last few seconds": at least two decisions, which come every 5 s or sooner. */
        const val WINDOW_MILLIS = 10_000L
    }
}

/**
 * Only changes the state once a new one has held for [holdMillis], so a short blip
 * (a stoplight, picking up the phone) doesn't flip it back and forth.
 *
 * Two exceptions switch straight away:
 *  - Driving, because the car's Bluetooth connecting is a definite signal. Leaving
 *    Driving still waits, which rides out a short Bluetooth drop.
 *  - Anything replacing "Not sure yet", so the first real state shows up quickly.
 *
 * Walking has a shorter hold ([walkingHoldMillis]), so it shows up sooner after you set off.
 */
class ContextSmoother(
    private val holdMillis: Long = HOLD_MILLIS,
    private val walkingHoldMillis: Long = WALKING_HOLD_MILLIS,
) {

    /** How long [state] has to hold before it's used. */
    fun holdFor(state: ContextState): Long =
        if (state.kind == ContextState.Kind.WALKING) walkingHoldMillis else holdMillis

    /** The state everyone sees. */
    var current: ContextState = ContextState.UNKNOWN
        private set

    /** A different state waiting to hold long enough, or null. */
    var pending: ContextState? = null
        private set

    /** When [pending] was first seen. */
    var pendingSinceMillis: Long = 0L
        private set

    /** Adds the latest decision. Returns the new state if it changed, otherwise null. */
    fun add(candidate: ContextState, timeMillis: Long): ContextState? {
        if (candidate == current) {
            pending = null
            return null
        }
        val switchNow = current.kind == ContextState.Kind.UNKNOWN ||
            candidate.kind == ContextState.Kind.DRIVING
        if (!switchNow) {
            if (candidate != pending) {
                // Something new: start its timer. A different candidate restarts it.
                pending = candidate
                pendingSinceMillis = timeMillis
                return null
            }
            if (timeMillis - pendingSinceMillis < holdFor(candidate)) return null
        }
        current = candidate
        pending = null
        return candidate
    }

    companion object {
        /** How long a new state has to hold before it's used. */
        const val HOLD_MILLIS = 30_000L

        /** The shorter hold for switching to Walking. */
        const val WALKING_HOLD_MILLIS = 20_000L
    }
}
