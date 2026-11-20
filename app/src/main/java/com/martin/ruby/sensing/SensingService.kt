package com.martin.ruby.sensing

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.martin.ruby.MainActivity
import com.martin.ruby.R
import com.martin.ruby.bluetooth.CarBluetooth
import com.martin.ruby.data.CarSettings
import com.martin.ruby.location.GpsReader
import com.martin.ruby.location.LocationSensing
import com.martin.ruby.location.LocationStatus
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
 * Motion sensing (Phase 3) and GPS (Phase 4) run from here too.
 */
class SensingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var motionSensor: MotionSensor? = null
    private var locationSensing: LocationSensing? = null
    private var shown = NotificationState()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android only lets a background service use GPS if it was started while Ruby was
        // on screen, or the user chose "Allow all the time" for location.
        val startedFromApp = intent?.getBooleanExtra(BackgroundSensing.EXTRA_FROM_APP, false) == true
        val wantLocation = GpsReader.hasFineLocation(this) &&
            (startedFromApp || GpsReader.hasBackgroundLocation(this))

        // Android requires this within a few seconds of every start.
        val usingLocation = when {
            wantLocation && startInForeground(withLocation = true) -> true
            startInForeground(withLocation = false) -> false
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (watchJob == null) {
            val settings = CarSettings(applicationContext)
            // After a reboot the saved state can be out of date, so ask Bluetooth directly.
            CarBluetooth.refreshSavedState(this, settings, scope)
            // Keep the notification text in step with the current state. Only the labels
            // are compared, so the notification doesn't redraw every 5 seconds.
            watchJob = scope.launch {
                combine(
                    settings.car,
                    settings.carConnected,
                    MotionStatus.reading,
                    LocationStatus.reading,
                ) { car, connected, motion, location ->
                    NotificationState(
                        driving = car != null && connected,
                        carName = car?.name,
                        motion = motion?.motion,
                        gpsVehicle = location.inVehicle,
                        place = location.place,
                    )
                }
                    .distinctUntilChanged()
                    .collect { state ->
                        shown = state
                        updateNotification(state)
                    }
            }
        }

        // Starts motion sensing, or picks up a newly granted Physical activity permission.
        val sensor = motionSensor ?: MotionSensor(this).also { motionSensor = it }
        sensor.start()

        if (usingLocation && locationSensing == null) {
            locationSensing = LocationSensing(this, scope).also { it.start() }
        }

        // If Android does stop the service to free memory, it restarts it when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        motionSensor?.stop()
        locationSensing?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /** Shows the ongoing notification and declares what the service uses. */
    private fun startInForeground(withLocation: Boolean): Boolean {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(shown), types)
            true
        } catch (e: Exception) {
            // For example, the Bluetooth permission was turned off in Settings (Android 14+
            // requires it for this kind of service), or Android blocked a background start.
            false
        }
    }

    /** Changes the notification text without restarting the service. */
    private fun updateNotification(state: NotificationState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return // Notifications are off, so there's nothing to update.
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    /** What the notification shows. */
    private data class NotificationState(
        val driving: Boolean = false,
        val carName: String? = null,
        val motion: Motion? = null,
        val gpsVehicle: Boolean = false,
        val place: String? = null,
    )

    private fun buildNotification(state: NotificationState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val carStatus = when {
            state.driving && state.carName != null -> "Driving (${state.carName})"
            state.driving -> "Driving"
            else -> "Not driving"
        }
        val movement = if (state.gpsVehicle) "In a vehicle (GPS)" else state.motion?.label
        val status = listOfNotNull(carStatus, movement, state.place?.let { "At $it" })
            .joinToString(" · ")

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Context sensing on")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
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
