package com.martin.ruby.location

import android.content.Context
import android.location.Location
import android.os.CancellationSignal
import android.os.SystemClock
import com.martin.ruby.data.CarSettings
import com.martin.ruby.data.Place
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.log.SensingLog
import com.martin.ruby.motion.Motion
import com.martin.ruby.motion.MotionReading
import com.martin.ruby.motion.MotionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Decides when to use GPS, and publishes what it finds to [LocationStatus].
 * Every decision is written to [SensingLog], so it's easy to see why GPS did or didn't run.
 *
 * GPS turns on:
 *  - at the first 5-second motion reading that looks like a vehicle, or
 *  - as soon as the phone connects to the car's Bluetooth (so it's warm before you move).
 * While on, it reads the speed every second. 10 seconds over 15 mph means "In a vehicle"
 * (dips under 15 mph of up to 3 seconds are tolerated, see [SpeedConfirmer]).
 *
 * "In a vehicle" is sticky. A single footstep-like bump doesn't end it; it only ends when:
 *  - there's been no movement for 2 minutes (or the phone is still and the sensors pause), or
 *  - the motion sensors report walking for 30 seconds in a row, with no GPS speed over 5 mph.
 *
 * To save battery, GPS turns off again after 2 minutes without movement (no vehicle-like
 * motion and no GPS speed over 5 mph), straight away once the motion sensors pause because
 * the phone is still, or after 3 minutes without a single fix.
 *
 * Separately, for saved places, it takes one quick fix when you arrive somewhere or every
 * few minutes while walking. Never if no places are saved.
 *
 * [scope] must run on the main thread. Runs inside the background service.
 */
class LocationSensing(private val context: Context, private val scope: CoroutineScope) {

    private val gps = GpsReader(context)
    private val speedConfirmer = SpeedConfirmer(log = SensingLog::add)
    private val jobs = mutableListOf<Job>()

    private var places = emptyList<Place>()
    private var lastMotion: Motion? = null
    private var lastPlaceCheckAt = Long.MIN_VALUE / 2
    private var pendingFix: CancellationSignal? = null

    /** While in a vehicle: when continuous walking started (the leave timer), or null. */
    private var walkingSinceAt: Long? = null

    // While GPS is on.
    private var watchJob: Job? = null
    private var gpsOnAt = 0L
    private var firstFixAt: Long? = null
    private var lastMovementAt = 0L
    private var lastGpsMovementAt = Long.MIN_VALUE / 2
    private var lastFixLoggedAt = Long.MIN_VALUE / 2

    /** After GPS fails to start or finds no fix, automatic starts wait until this time. */
    private var retryAt = 0L

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
        jobs += scope.launch {
            // Connecting to the car is the earliest sign of a drive; get GPS ready.
            val settings = CarSettings(context)
            var wasConnected: Boolean? = null
            combine(settings.car, settings.carConnected) { car, connected -> car != null && connected }
                .distinctUntilChanged()
                .collect { connected ->
                    // Ignore the value read at startup; only react to a new connection.
                    if (connected && wasConnected == false) startGps("car Bluetooth connected")
                    wasConnected = connected
                }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (gps.isRunning) SensingLog.add("GPS off (sensing stopped).")
        watchJob?.cancel()
        gps.stopUpdates()
        pendingFix?.cancel()
        walkingSinceAt = null
        LocationStatus.reset()
    }

    /** Turns GPS on right now, ignoring motion and any retry wait. For testing. */
    fun testNow() {
        SensingLog.add("Test requested.")
        if (gps.isRunning) {
            SensingLog.add("GPS is already on.")
            return
        }
        retryAt = 0L
        startGps("test")
    }

    private fun onMotion(reading: MotionReading) {
        val now = SystemClock.elapsedRealtime()
        val motion = reading.motion

        val changed = motion != lastMotion
        lastMotion = motion
        if (changed) SensingLog.add("Motion: ${motion.label}${if (reading.paused) " (sensors paused)" else ""}.")

        checkWalkingAway(reading, now)

        when {
            // GPS says we're moving: trust it over the motion sensors. A phone in a mount on
            // a smooth road can feel "still", and a bump can look like a footstep.
            movingByGps(now) && (reading.paused || reading.raw == Motion.WALKING || reading.raw == Motion.STILL) -> Unit

            // The phone has been still for 2 minutes (that's when the sensors pause).
            reading.paused -> {
                if (gps.isRunning) stopGps("Phone still for 2 minutes.")
                leaveVehicle("Phone still for a while")
            }
            // Use the raw reading: the smoothed one waits 15 s before saying "vehicle".
            reading.raw == Motion.IN_VEHICLE -> {
                lastMovementAt = now
                startGps("motion looks like a vehicle")
            }
            // Bumps and turns in a car can read as "Moving"; that keeps GPS on, but
            // doesn't turn it on (picking up the phone would do that too often).
            reading.raw == Motion.UNKNOWN && gps.isRunning -> lastMovementAt = now
        }

        val walkingAWhile = motion == Motion.WALKING && now - lastPlaceCheckAt >= WALKING_RECHECK_MS
        if (changed || walkingAWhile) checkPlace(now)
    }

    /** True if GPS showed a speed over 5 mph in the last 15 seconds. */
    private fun movingByGps(now: Long): Boolean =
        gps.isRunning && now - lastGpsMovementAt <= GPS_MOVEMENT_TRUST_MS

    /**
     * The leave timer: while in a vehicle, walking has to last [WALK_OUT_MS] without a break
     * (and without GPS showing over 5 mph, see [onFix]) before we say you've left it.
     */
    private fun checkWalkingAway(reading: MotionReading, now: Long) {
        if (!LocationStatus.reading.value.inVehicle) {
            walkingSinceAt = null
            return
        }
        val since = walkingSinceAt
        if (reading.paused || reading.raw != Motion.WALKING) {
            if (since != null) {
                walkingSinceAt = null
                SensingLog.add("Walking stopped after ${(now - since) / 1000} s (now ${reading.raw.label}): leave timer cancelled, still in a vehicle.")
            }
            return
        }
        if (since == null) {
            // GPS showed movement in the last 15 s: a bump on the road, not footsteps.
            if (movingByGps(now)) return
            walkingSinceAt = now
            SensingLog.add(
                "Walking while in a vehicle: leaving if it lasts %d s with GPS under %.0f mph."
                    .format(WALK_OUT_MS / 1000, MOVING_MPH)
            )
            return
        }
        if (now - since >= WALK_OUT_MS) {
            leaveVehicle("Walking for %d s with GPS under %.0f mph".format((now - since) / 1000, MOVING_MPH))
        }
    }

    private fun leaveVehicle(why: String) {
        walkingSinceAt = null
        if (!LocationStatus.reading.value.inVehicle) return
        SensingLog.add("$why: no longer in a vehicle.")
        LocationStatus.update { it.copy(inVehicle = false) }
    }

    // GPS on and off

    private fun startGps(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (gps.isRunning) return
        if (now < retryAt) {
            return skip("GPS wanted ($reason), but waiting until ${clockTime(retryAt)} after the last problem.")
        }

        speedConfirmer.reset()
        firstFixAt = null
        gpsOnAt = now
        lastMovementAt = now
        val error = gps.startUpdates(::onFix)
        if (error != null) {
            retryAt = now + RETRY_MS
            skip("GPS couldn't start ($reason): $error Trying again in 1 minute.")
            return
        }
        lastSkipReason = null
        SensingLog.add("GPS on ($reason). Reading speed every second.")
        LocationStatus.update { it.copy(gpsOn = true) }

        watchJob = scope.launch {
            var noFixLogged = 0
            while (true) {
                delay(WATCH_EVERY_MS)
                val t = SystemClock.elapsedRealtime()
                if (firstFixAt == null) {
                    val waited = t - gpsOnAt
                    if (waited >= NO_FIX_GIVE_UP_MS) {
                        retryAt = t + RETRY_MS
                        stopGps("No GPS fix in ${waited / 1000} s; it may not see the sky. Trying again after ${clockTime(retryAt)}.")
                        return@launch
                    }
                    if (waited / 30_000L > noFixLogged) {
                        noFixLogged++
                        SensingLog.add("No GPS fix yet after ${waited / 1000} s (finding satellites can take a minute).")
                    }
                }
                if (t - lastMovementAt >= STILL_OFF_MS) {
                    stopGps("No movement for ${STILL_OFF_MS / 60_000} minutes.")
                    leaveVehicle("Stopped")
                    return@launch
                }
            }
        }
    }

    private fun stopGps(why: String) {
        gps.stopUpdates()
        watchJob?.cancel()
        watchJob = null
        LocationStatus.update { it.copy(gpsOn = false) }
        SensingLog.add("$why GPS off.")
    }

    private fun onFix(location: Location) {
        val now = SystemClock.elapsedRealtime()
        if (firstFixAt == null) {
            firstFixAt = now
            SensingLog.add("First GPS fix after ${(now - gpsOnAt) / 1000} s.")
        }
        matchPlace(location)

        // GPS gives a fix every second; log one line every 5 seconds so the log stays readable.
        // The screen still updates every second.
        val logThisFix = now - lastFixLoggedAt >= FIX_LOG_GAP_MS
        if (logThisFix) lastFixLoggedAt = now
        val accuracy = if (location.hasAccuracy()) " ±%.0f m".format(location.accuracy) else ""
        if (!location.hasSpeed()) {
            if (logThisFix) SensingLog.add("Fix with no speed$accuracy.")
            return
        }
        val speed = location.speed
        val mph = speed * METERS_PER_SECOND_TO_MPH
        if (logThisFix) SensingLog.add("Speed %.0f mph$accuracy.".format(mph))
        LocationStatus.update { it.copy(speedMph = mph) }
        if (mph >= MOVING_MPH) {
            lastMovementAt = now
            lastGpsMovementAt = now
            walkingSinceAt?.let { since ->
                walkingSinceAt = null
                SensingLog.add(
                    "GPS shows %.0f mph after %d s of walking: leave timer cancelled, still in a vehicle."
                        .format(mph, (now - since) / 1000)
                )
            }
        }

        if (speedConfirmer.add(speed, now) && !LocationStatus.reading.value.inVehicle) {
            LocationStatus.update { it.copy(inVehicle = true) }
            SensingLog.add(
                "Over %.0f mph for %d s: in a vehicle (%.0f s after GPS on)."
                    .format(SpeedConfirmer.MIN_MPH, SpeedConfirmer.HOLD_MILLIS / 1000, (now - gpsOnAt) / 1000f)
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
        const val STILL_OFF_MS = 2 * 60_000L           // GPS off after 2 minutes without movement
        const val MOVING_MPH = 5f                      // GPS speed that counts as movement
        const val GPS_MOVEMENT_TRUST_MS = 15_000L      // GPS movement this recent beats the motion sensors
        const val WALK_OUT_MS = 30_000L                // walking this long (GPS under 5 mph) ends "in a vehicle"
        const val NO_FIX_GIVE_UP_MS = 3 * 60_000L      // give up if no fix at all in 3 minutes
        const val RETRY_MS = 60_000L                   // then wait a minute before trying again
        const val WATCH_EVERY_MS = 5_000L
        const val PLACE_CHECK_MIN_GAP_MS = 2 * 60_000L // at most one place fix every 2 minutes
        const val WALKING_RECHECK_MS = 3 * 60_000L     // while walking, check every 3 minutes
        const val FIX_LOG_GAP_MS = 5_000L              // log at most one fix every 5 seconds
    }
}
