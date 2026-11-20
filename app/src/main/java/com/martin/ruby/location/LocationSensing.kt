package com.martin.ruby.location

import android.content.Context
import android.location.Location
import android.os.CancellationSignal
import android.os.SystemClock
import com.martin.ruby.data.Place
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.motion.Motion
import com.martin.ruby.motion.MotionReading
import com.martin.ruby.motion.MotionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Decides when to use GPS, and publishes what it finds to [LocationStatus].
 *
 * GPS uses a lot of battery, so it's only switched on:
 *  - when motion suggests a vehicle: up to 2 minutes, to check the speed, and
 *  - for one quick fix when you arrive somewhere or every few minutes while walking,
 *    to see if you're at a saved place. Never if no places are saved.
 *
 * [scope] must run on the main thread. Runs inside the background service.
 */
class LocationSensing(private val context: Context, private val scope: CoroutineScope) {

    private val gps = GpsReader(context)
    private val speedConfirmer = SpeedConfirmer()
    private val jobs = mutableListOf<Job>()

    private var places = emptyList<Place>()
    private var lastMotion: Motion? = null
    private var vehicleCheckTimeout: Job? = null
    private var nextVehicleCheckAt = 0L
    private var lastPlaceCheckAt = Long.MIN_VALUE / 2
    private var pendingFix: CancellationSignal? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            PlaceStore(context).places.collect { saved ->
                places = saved
                // A deleted place shouldn't stay on screen.
                LocationStatus.update { current ->
                    if (saved.none { it.name == current.place }) current.copy(place = null) else current
                }
            }
        }
        jobs += scope.launch { MotionStatus.reading.collect { it?.let(::onMotion) } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        vehicleCheckTimeout?.cancel()
        gps.stopUpdates()
        pendingFix?.cancel()
        LocationStatus.reset()
    }

    private fun onMotion(reading: MotionReading) {
        val now = SystemClock.elapsedRealtime()
        val motion = reading.motion

        // Out of the vehicle: walking, or the phone has been still long enough to pause.
        if (motion == Motion.WALKING || reading.paused) {
            LocationStatus.update { it.copy(inVehicle = false) }
        }

        if (motion == Motion.IN_VEHICLE) startVehicleCheck(now)

        val changed = motion != lastMotion
        lastMotion = motion
        val walkingAWhile = motion == Motion.WALKING && now - lastPlaceCheckAt >= WALKING_RECHECK_MS
        if (changed || walkingAWhile) checkPlace(now)
    }

    // Speed check

    private fun startVehicleCheck(now: Long) {
        if (gps.isRunning || now < nextVehicleCheckAt || LocationStatus.reading.value.inVehicle) return
        speedConfirmer.reset()
        if (!gps.startUpdates(::onFix)) {
            nextVehicleCheckAt = now + RETRY_MS // GPS off or not allowed; try again later
            return
        }
        LocationStatus.update { it.copy(gpsOn = true) }
        vehicleCheckTimeout = scope.launch {
            delay(VEHICLE_CHECK_MS)
            endVehicleCheck()
        }
    }

    private fun endVehicleCheck() {
        gps.stopUpdates()
        vehicleCheckTimeout?.cancel()
        vehicleCheckTimeout = null
        nextVehicleCheckAt = SystemClock.elapsedRealtime() + COOLDOWN_MS
        LocationStatus.update { it.copy(gpsOn = false) }
    }

    private fun onFix(location: Location) {
        matchPlace(location)
        if (!location.hasSpeed()) return

        val speed = location.speed
        LocationStatus.update { it.copy(speedMph = speed * METERS_PER_SECOND_TO_MPH) }
        if (speedConfirmer.add(speed, SystemClock.elapsedRealtime())) {
            LocationStatus.update { it.copy(inVehicle = true) }
            endVehicleCheck() // Confirmed, so GPS can go off straight away.
        }
    }

    // Saved places

    private fun checkPlace(now: Long) {
        if (places.isEmpty()) return     // nothing to look for, so don't use GPS at all
        if (gps.isRunning) return        // fixes are already coming in
        if (pendingFix != null) return
        if (now - lastPlaceCheckAt < PLACE_CHECK_MIN_GAP_MS) return
        lastPlaceCheckAt = now
        pendingFix = gps.singleFix { location ->
            pendingFix = null
            location?.let(::matchPlace)
        }
    }

    private fun matchPlace(location: Location) {
        if (location.hasAccuracy() && location.accuracy > PlaceMatcher.MAX_ACCURACY_METERS) return
        val place = PlaceMatcher.placeAt(places, location.latitude, location.longitude)
        LocationStatus.update { it.copy(place = place?.name) }
    }

    private companion object {
        const val VEHICLE_CHECK_MS = 2 * 60_000L       // GPS on for at most 2 minutes
        const val COOLDOWN_MS = 5 * 60_000L            // then wait 5 minutes before trying again
        const val RETRY_MS = 60_000L                   // if GPS couldn't start at all
        const val PLACE_CHECK_MIN_GAP_MS = 2 * 60_000L // at most one place fix every 2 minutes
        const val WALKING_RECHECK_MS = 3 * 60_000L     // while walking, check every 3 minutes
    }
}
