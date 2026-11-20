package com.martin.ruby.location

import android.content.Context
import android.location.Location
import android.os.CancellationSignal
import android.os.SystemClock
import com.martin.ruby.data.Place
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.log.SensingLog
import com.martin.ruby.motion.Motion
import com.martin.ruby.motion.MotionReading
import com.martin.ruby.motion.MotionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Decides when to use GPS, and publishes what it finds to [LocationStatus].
 * Every decision is written to [SensingLog], so it's easy to see why GPS did or didn't run.
 *
 * GPS uses a lot of battery, so it's only switched on:
 *  - when motion suggests a vehicle: until 2 minutes after the first fix (4 minutes at
 *    most), to check the speed, and
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
    private var lastPlaceCheckAt = Long.MIN_VALUE / 2
    private var pendingFix: CancellationSignal? = null

    // The current speed check, if GPS is on for one.
    private var checkJob: Job? = null
    private var firstFixAt: Long? = null
    private var fixCount = 0
    private var topMph = 0f
    private var lastFixLoggedAt = Long.MIN_VALUE / 2
    private var nextVehicleCheckAt = 0L

    /** The last "GPS not started because..." message, so it's logged once, not every 5 s. */
    private var lastSkipReason: String? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        SensingLog.add("Location sensing started.")
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
        if (gps.isRunning) SensingLog.add("GPS off (sensing stopped).")
        checkJob?.cancel()
        gps.stopUpdates()
        pendingFix?.cancel()
        LocationStatus.reset()
    }

    /** Runs a speed check right now, ignoring motion and the cooldown. For testing. */
    fun testNow() {
        SensingLog.add("Test requested.")
        if (gps.isRunning) {
            SensingLog.add("GPS is already on.")
            return
        }
        nextVehicleCheckAt = 0L
        startVehicleCheck(SystemClock.elapsedRealtime(), reason = "test")
    }

    private fun onMotion(reading: MotionReading) {
        val now = SystemClock.elapsedRealtime()
        val motion = reading.motion

        // Out of the vehicle: walking, or the phone has been still long enough to pause.
        if ((motion == Motion.WALKING || reading.paused) && LocationStatus.reading.value.inVehicle) {
            SensingLog.add(if (reading.paused) "Phone still for a while: no longer in a vehicle." else "Walking: no longer in a vehicle.")
            LocationStatus.update { it.copy(inVehicle = false) }
        }

        val changed = motion != lastMotion
        lastMotion = motion
        if (changed) SensingLog.add("Motion: ${motion.label}${if (reading.paused) " (sensors paused)" else ""}.")

        if (motion == Motion.IN_VEHICLE) startVehicleCheck(now, reason = "motion suggests a vehicle")
        val walkingAWhile = motion == Motion.WALKING && now - lastPlaceCheckAt >= WALKING_RECHECK_MS
        if (changed || walkingAWhile) checkPlace(now)
    }

    // Speed check

    private fun startVehicleCheck(now: Long, reason: String) {
        when {
            gps.isRunning -> return
            LocationStatus.reading.value.inVehicle -> return skip("Already confirmed in a vehicle.")
            now < nextVehicleCheckAt -> return skip(
                "Motion suggests a vehicle; GPS waits until ${clockTime(nextVehicleCheckAt)} " +
                    "before trying again."
            )
        }

        speedConfirmer.reset()
        firstFixAt = null
        fixCount = 0
        topMph = 0f
        val error = gps.startUpdates(::onFix)
        if (error != null) {
            nextVehicleCheckAt = now + RETRY_MS
            skip("GPS couldn't start ($reason): $error Trying again in 1 minute.")
            return
        }
        lastSkipReason = null
        SensingLog.add("GPS on ($reason). Checking speed.")
        LocationStatus.update { it.copy(gpsOn = true) }

        val startedAt = now
        checkJob = scope.launch {
            // Watch the check every 15 seconds until it confirms or times out.
            while (true) {
                delay(15_000L)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                val firstFix = firstFixAt
                when {
                    firstFix == null && elapsed >= MAX_CHECK_MS -> {
                        endVehicleCheck("No GPS fix in ${elapsed / 1000} s. It may not see the sky.")
                        return@launch
                    }
                    firstFix == null && elapsed % 30_000L < 15_000L -> SensingLog.add(
                        "No GPS fix yet after ${elapsed / 1000} s (finding satellites can take a minute or more)."
                    )
                    firstFix != null &&
                        (SystemClock.elapsedRealtime() - firstFix >= CHECK_AFTER_FIX_MS || elapsed >= MAX_CHECK_MS) -> {
                        endVehicleCheck(
                            "Speed not held over %.0f mph (top speed %.0f mph, %d fixes)."
                                .format(SpeedConfirmer.MIN_MPH, topMph, fixCount)
                        )
                        return@launch
                    }
                }
            }
        }
    }

    private fun endVehicleCheck(result: String, confirmed: Boolean = false) {
        gps.stopUpdates()
        checkJob?.cancel()
        checkJob = null
        LocationStatus.update { it.copy(gpsOn = false) }
        if (confirmed) {
            SensingLog.add("$result GPS off.")
        } else {
            nextVehicleCheckAt = SystemClock.elapsedRealtime() + COOLDOWN_MS
            SensingLog.add("$result GPS off. Next try after ${clockTime(nextVehicleCheckAt)}.")
        }
    }

    private fun onFix(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (firstFixAt == null) {
            firstFixAt = now
            SensingLog.add("First GPS fix.")
        }
        fixCount++
        matchPlace(location)

        // GPS gives a fix every second; log one line every 5 seconds so the log stays readable.
        val logThisFix = now - lastFixLoggedAt >= FIX_LOG_GAP_MS
        if (logThisFix) lastFixLoggedAt = now
        val accuracy = if (location.hasAccuracy()) " ±%.0f m".format(location.accuracy) else ""
        if (!location.hasSpeed()) {
            if (logThisFix) SensingLog.add("Fix with no speed$accuracy.")
            return
        }
        val speed = location.speed
        val mph = speed * METERS_PER_SECOND_TO_MPH
        topMph = maxOf(topMph, mph)
        if (logThisFix) SensingLog.add("Speed %.0f mph$accuracy.".format(mph))
        LocationStatus.update { it.copy(speedMph = mph) }

        if (speedConfirmer.add(speed, now)) {
            LocationStatus.update { it.copy(inVehicle = true) }
            endVehicleCheck(
                "Over %.0f mph for %d s: in a vehicle."
                    .format(SpeedConfirmer.MIN_MPH, SpeedConfirmer.HOLD_MILLIS / 1000),
                confirmed = true,
            )
        }
    }

    // Saved places

    private fun checkPlace(now: Long) {
        if (places.isEmpty()) return     // nothing to look for, so don't use GPS at all
        if (gps.isRunning) return        // fixes are already coming in
        if (pendingFix != null) return
        if (now - lastPlaceCheckAt < PLACE_CHECK_MIN_GAP_MS) return
        lastPlaceCheckAt = now
        SensingLog.add("Checking saved places (one GPS fix).")
        pendingFix = gps.singleFix { location ->
            pendingFix = null
            if (location == null) {
                SensingLog.add("Place check: no GPS fix.")
            } else {
                matchPlace(location)
                SensingLog.add("Place check: ${LocationStatus.reading.value.place ?: "not at a saved place"}.")
            }
        }
    }

    private fun matchPlace(location: Location) {
        if (location.hasAccuracy() && location.accuracy > PlaceMatcher.MAX_ACCURACY_METERS) return
        val place = PlaceMatcher.placeAt(places, location.latitude, location.longitude)
        LocationStatus.update { it.copy(place = place?.name) }
    }

    // Logging helpers

    /** Logs why GPS didn't start, but only when the reason changes. */
    private fun skip(reason: String) {
        if (reason == lastSkipReason) return
        lastSkipReason = reason
        SensingLog.add(reason)
    }

    /** Turns an elapsedRealtime value into a wall-clock time like "14:32". */
    private fun clockTime(elapsedRealtime: Long): String {
        val wallClock = System.currentTimeMillis() + (elapsedRealtime - SystemClock.elapsedRealtime())
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(wallClock))
    }

    private companion object {
        const val CHECK_AFTER_FIX_MS = 2 * 60_000L     // check speed for 2 minutes after the first fix
        const val MAX_CHECK_MS = 4 * 60_000L           // but never keep GPS on longer than 4 minutes
        const val COOLDOWN_MS = 3 * 60_000L            // then wait 3 minutes before trying again
        const val RETRY_MS = 60_000L                   // if GPS couldn't start at all
        const val PLACE_CHECK_MIN_GAP_MS = 2 * 60_000L // at most one place fix every 2 minutes
        const val WALKING_RECHECK_MS = 3 * 60_000L     // while walking, check every 3 minutes
        const val FIX_LOG_GAP_MS = 5_000L              // log at most one fix every 5 seconds
    }
}
