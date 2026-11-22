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

    fun update(reading: ContextReading) {
        _reading.value = reading
    }

    fun clear() {
        _reading.value = null
    }
}
