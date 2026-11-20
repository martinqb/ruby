package com.martin.ruby.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest motion reading, shared between the background service and the screen.
 *
 * It changes every 5 seconds, so it's kept in memory rather than saved to disk.
 * Null means motion sensing isn't running.
 */
object MotionStatus {
    private val _reading = MutableStateFlow<MotionReading?>(null)
    val reading: StateFlow<MotionReading?> = _reading.asStateFlow()

    fun update(reading: MotionReading) {
        _reading.value = reading
    }

    fun clear() {
        _reading.value = null
    }
}
