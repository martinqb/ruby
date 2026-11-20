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
 * Phase 3 will run the motion sensors from here.
 */
class SensingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires this within a few seconds of the service starting.
        if (!showNotification(buildNotification(driving = false, carName = null))) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (watchJob == null) {
            val settings = CarSettings(applicationContext)
            // After a reboot the saved state can be out of date, so ask Bluetooth directly.
            CarBluetooth.refreshSavedState(this, settings, scope)
            // Keep the notification text in step with the current state.
            watchJob = scope.launch {
                combine(settings.car, settings.carConnected) { car, connected ->
                    (car != null && connected) to car?.name
                }
                    .distinctUntilChanged()
                    .collect { (driving, carName) ->
                        showNotification(buildNotification(driving, carName))
                    }
            }
        }

        // If Android does stop the service to free memory, it restarts it when it can.
        return START_STICKY
    }

    override fun onDestroy() {
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

    private fun buildNotification(driving: Boolean, carName: String?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = when {
            driving && carName != null -> "Driving ($carName)"
            driving -> "Driving"
            else -> "Not driving"
        }
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
