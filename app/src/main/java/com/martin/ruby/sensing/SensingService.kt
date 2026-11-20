package com.martin.ruby.sensing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.martin.ruby.MainActivity
import com.martin.ruby.R
import com.martin.ruby.bluetooth.CarBluetooth
import com.martin.ruby.data.CarSettings
import com.martin.ruby.motion.Motion
import com.martin.ruby.motion.MotionSensor
import com.martin.ruby.motion.MotionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps Ruby running in the background, with a small ongoing notification.
 *
 * A foreground service is Android's approved way to run continuously: the notification
 * tells the user something is running, and in return Android doesn't kill the app.
 * Motion sensing (Phase 3) runs from here too.
 */
class SensingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var motionSensor: MotionSensor? = null
    private var shown = NotificationState()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires this within a few seconds of every start. Use the latest state, so reopening Ruby
        // doesn't reset the text.
        if (!showNotification(buildNotification(shown))) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (watchJob == null) {
            val settings = CarSettings(applicationContext)
            // After a reboot the saved state can be out of date, so ask Bluetooth directly.
            CarBluetooth.refreshSavedState(this, settings, scope)
            // Keep the notification text in step with the current state. Only the labels
            // are compared, so the notification doesn't redraw every 5 seconds.
            watchJob = scope.launch {
                combine(settings.car, settings.carConnected, MotionStatus.reading) { car, connected, reading ->
                    NotificationState(car != null && connected, car?.name, reading?.motion)
                }
                    .distinctUntilChanged()
                    .collect { state ->
                        shown = state
                        showNotification(buildNotification(state))
                    }
            }
        }

        // Starts motion sensing, or picks up a newly granted Physical activity permission.
        val sensor = motionSensor ?: MotionSensor(this).also { motionSensor = it }
        sensor.start()

        // If Android does stop the service to free memory, it restarts it when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        motionSensor?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** Shows or updates the ongoing notification. Returns false if Android refused. */
    private fun showNotification(notification: Notification): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (e: Exception) {
            // For example, the Bluetooth permission was turned off in Settings (Android 14+
            // requires it for this kind of service), or Android blocked a background start.
            false
        }
    }

    /** What the notification shows. */
    private data class NotificationState(
        val driving: Boolean = false,
        val carName: String? = null,
        val motion: Motion? = null,
    )

    private fun buildNotification(state: NotificationState): Notification {
        val (driving, carName, motion) = state
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val carStatus = when {
            driving && carName != null -> "Driving ($carName)"
            driving -> "Driving"
            else -> "Not driving"
        }
        val status = if (motion == null) carStatus else "$carStatus · ${motion.label}"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Context sensing on")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Context sensing",
            NotificationManager.IMPORTANCE_LOW, // No sound or pop-up, just the icon.
        ).apply {
            description = "Shows that Ruby is sensing in the background."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "sensing"
        const val NOTIFICATION_ID = 1
    }
}
