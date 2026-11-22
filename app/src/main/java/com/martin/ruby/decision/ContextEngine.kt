package com.martin.ruby.decision

import android.content.Context
import android.os.SystemClock
import com.martin.ruby.data.CarSettings
import com.martin.ruby.location.LocationStatus
import com.martin.ruby.log.SensingLog
import com.martin.ruby.motion.MotionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The decision engine (Phase 5): combines car Bluetooth, GPS, saved places and motion
 * into one current state, published to [ContextStatus].
 *
 * It only reads what the other parts already worked out. GPS speed rules (dip tolerance,
 * staying "in a vehicle" at stoplights) live in LocationSensing; this just ranks the
 * results and waits for a new state to hold before switching (30 s, or 20 s for Walking).
 *
 * [scope] must run on the main thread. Runs inside the background service.
 */
class ContextEngine(private val context: Context, private val scope: CoroutineScope) {

    private val smoother = ContextSmoother()
    private var job: Job? = null
    private var reason = ""
    private var sinceMillis = 0L

    fun start() {
        if (job != null) return
        val settings = CarSettings(context)

        // Re-check every few seconds even if no input changes, so a pending state
        // switches once it has held long enough.
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(TICK_MS)
            }
        }
        job = scope.launch {
            combine(
                settings.car,
                settings.carConnected,
                LocationStatus.reading,
                MotionStatus.reading,
                ticks,
            ) { car, connected, location, motion, _ ->
                ContextInputs(
                    carConnected = car != null && connected,
                    inVehicle = location.inVehicle,
                    place = location.place,
                    motion = motion?.motion,
                    motionPaused = motion?.paused == true,
                )
            }.collect(::onInputs)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        ContextStatus.clear()
    }

    private fun onInputs(inputs: ContextInputs) {
        val now = SystemClock.elapsedRealtime()
        val decision = ContextRules.decide(inputs)
        val previous = smoother.current

        // Only real changes are logged. A pending state isn't: motion can flicker every
        // 5 s and would flood the log. The screen shows the pending state instead.
        val changed = smoother.add(decision.state, now)
        if (changed != null) {
            val wall = System.currentTimeMillis()
            val held = if (previous.kind == ContextState.Kind.UNKNOWN) "" else
                " Was ${previous.label} for ${formatDuration(wall - sinceMillis)}."
            SensingLog.add("Now ${changed.label} (${decision.reason}).$held")
            sinceMillis = wall
        }
        // Keep the reason current, e.g. "motion: still" becoming "sensors paused".
        if (decision.state == smoother.current) reason = decision.reason

        ContextStatus.update(
            ContextReading(
                state = smoother.current,
                reason = reason,
                sinceMillis = sinceMillis,
                pending = smoother.pending,
                pendingSinceMillis = smoother.pending?.let {
                    System.currentTimeMillis() - (now - smoother.pendingSinceMillis)
                } ?: 0L,
                pendingHoldMillis = smoother.pending?.let(smoother::holdFor) ?: 0L,
            )
        )
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "%d h %02d min".format(seconds / 3600, seconds / 60 % 60)
        }
    }

    private companion object {
        const val TICK_MS = 5_000L
    }
}
