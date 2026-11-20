package com.martin.ruby.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What GPS has found, shared between the background service and the screen. */
data class LocationReading(
    /** True while GPS is on, checking the speed. */
    val gpsOn: Boolean = false,
    /** The latest GPS speed, or null if there hasn't been one. */
    val speedMph: Float? = null,
    /** True once GPS speed has confirmed you're in a vehicle. */
    val inVehicle: Boolean = false,
    /** The saved place you're at, or null. */
    val place: String? = null,
)

/** Kept in memory, like MotionStatus; it changes often and is worked out again after a restart. */
object LocationStatus {
    private val _reading = MutableStateFlow(LocationReading())
    val reading: StateFlow<LocationReading> = _reading.asStateFlow()

    fun update(change: (LocationReading) -> LocationReading) {
        _reading.update(change)
    }

    fun reset() {
        _reading.value = LocationReading()
    }
}
