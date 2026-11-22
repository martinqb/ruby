package com.martin.ruby.decision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The decision engine's output, shared between the background service and the screen. */
data class ContextReading(
    val state: ContextState,
    /** Why it's in [state]. */
    val reason: String,
    /** Wall-clock time [state] started. */
    val sinceMillis: Long,
    /** A different state waiting to hold long enough, or null. */
    val pending: ContextState? = null,
    /** Wall-clock time [pending] was first seen. */
    val pendingSinceMillis: Long = 0L,
    /** How long [pending] has to hold (30 s, or 20 s for Walking). */
    val pendingHoldMillis: Long = 0L,
)

/** Kept in memory, like MotionStatus. Null means the decision engine isn't running. */
object ContextStatus {
    private val _reading = MutableStateFlow<ContextReading?>(null)
    val reading: StateFlow<ContextReading?> = _reading.asStateFlow()

    private val recent = RecentStates()

    fun update(reading: ContextReading) {
        _reading.value = reading
    }

    /** Records one decision (before the 30 s hold). Times are SystemClock.elapsedRealtime(). */
    @Synchronized
    fun record(state: ContextState, timeMillis: Long) = recent.add(state, timeMillis)

    /** The strongest decision of the last 10 s, or null if the engine isn't running. */
    @Synchronized
    fun strongestRecent(timeMillis: Long): ContextState? = recent.strongest(timeMillis)

    @Synchronized
    fun clear() {
        _reading.value = null
        recent.clear()
    }
}
