package com.martin.ruby.motion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * Reads the accelerometer and step detector, and every 5 seconds publishes a
 * [MotionReading] to [MotionStatus].
 *
 * To save battery: while you're moving, it keeps the phone's processor awake so no
 * readings are missed. After 2 quiet minutes it turns the sensors off and asks the
 * significant motion sensor (a low-power chip) to wake it when the phone starts moving.
 */
class MotionSensor(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stepDetector = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val significantMotion = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Ruby:motion")
        .apply { setReferenceCounted(false) }

    private var listening = false     // accelerometer on
    private var countingSteps = false // step detector on
    private var sleeping = false      // sensors off, waiting for significant motion

    // The current 5-second window.
    private val magnitudes = mutableListOf<Float>()
    private var windowStartNanos = 0L
    private var stepsInWindow = 0

    /** How many windows in a row had no walking or vehicle motion. */
    private var quietWindows = 0

    private val wakeOnMovement = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            sleeping = false
            start()
        }
    }

    /** Starts sensing. Safe to call again, e.g. after the Physical activity permission is granted. */
    fun start() {
        val sensorManager = sensorManager ?: return
        if (sleeping || accelerometer == null) return

        if (!listening) {
            listening = sensorManager.registerListener(
                this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL
            )
            resetWindow()
            quietWindows = 0
            keepAwake()
        }
        if (listening && !countingSteps && stepDetector != null && hasActivityPermission(context)) {
            countingSteps = sensorManager.registerListener(
                this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        significantMotion?.let { sensorManager?.cancelTriggerSensor(wakeOnMovement, it) }
        listening = false
        countingSteps = false
        sleeping = false
        if (wakeLock.isHeld) wakeLock.release()
        MotionStatus.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> stepsInWindow++
            Sensor.TYPE_ACCELEROMETER -> onAcceleration(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onAcceleration(event: SensorEvent) {
        val (x, y, z) = event.values
        magnitudes += sqrt(x * x + y * y + z * z)

        if (windowStartNanos == 0L) windowStartNanos = event.timestamp
        val elapsedNanos = event.timestamp - windowStartNanos
        if (elapsedNanos >= WINDOW_NANOS) finishWindow(seconds = elapsedNanos / 1_000_000_000f)
    }

    private fun finishWindow(seconds: Float) {
        val variation = MotionClassifier.variation(magnitudes)
        val stepsPerSecond = if (countingSteps) stepsInWindow / seconds else null
        val motion = MotionClassifier.classify(variation, stepsPerSecond)
        MotionStatus.update(MotionReading(motion, variation, stepsPerSecond))
        resetWindow()

        if (motion == Motion.WALKING || motion == Motion.IN_VEHICLE) {
            quietWindows = 0
        } else {
            quietWindows++
        }

        when {
            quietWindows < QUIET_WINDOWS_BEFORE_SLEEP -> keepAwake()
            !sleepUntilMoving() -> {
                // No significant motion sensor on this phone: keep the sensors on, but let
                // the processor sleep. Readings continue whenever the phone is awake anyway.
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    /** Turns sensors off until the phone moves. Returns false if this phone can't do that. */
    private fun sleepUntilMoving(): Boolean {
        val sensorManager = sensorManager ?: return false
        val trigger = significantMotion ?: return false
        if (!sensorManager.requestTriggerSensor(wakeOnMovement, trigger)) return false

        sensorManager.unregisterListener(this)
        listening = false
        countingSteps = false
        sleeping = true
        if (wakeLock.isHeld) wakeLock.release()
        MotionStatus.update(MotionReading(Motion.STILL, variation = 0f, stepsPerSecond = null, paused = true))
        return true
    }

    private fun resetWindow() {
        magnitudes.clear()
        windowStartNanos = 0L
        stepsInWindow = 0
    }

    /** Keeps the processor awake for a bit longer. Re-armed every window while moving,
     *  so if readings ever stop arriving, the phone is allowed to sleep after a minute. */
    private fun keepAwake() {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    companion object {
        private const val WINDOW_NANOS = 5_000_000_000L // 5 seconds
        private const val QUIET_WINDOWS_BEFORE_SLEEP = 24 // 24 x 5 s = 2 minutes
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L

        /** The step detector needs the "Physical activity" permission (Android 10+). */
        fun hasActivityPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
