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
import com.martin.ruby.decision.ContextEngine
import com.martin.ruby.decision.ContextState
import com.martin.ruby.decision.ContextStatus
import com.martin.ruby.location.GpsReader
import com.martin.ruby.location.LocationSensing
import com.martin.ruby.log.SensingLog
import com.martin.ruby.motion.MotionSensor
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
 * Motion sensing (Phase 3), GPS (Phase 4) and the decision engine (Phase 5) run from here too.
 */
class SensingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var motionSensor: MotionSensor? = null
    private var locationSensing: LocationSensing? = null
    private var contextEngine: ContextEngine? = null
    private var shown = NotificationState()
    private var lastStartMessage: String? = null

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
        val locationError = if (wantLocation) startInForeground(withLocation = true) else "not wanted"
        if (locationError != null) {
            val error = startInForeground(withLocation = false)
            if (error != null) {
                SensingLog.add("Service couldn't start: $error")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val startMessage = "Service running" +
            if (locationError == null) " with location access." else when {
                !GpsReader.hasFineLocation(this) -> ". Precise location isn't allowed."
                !wantLocation -> " without location access: \"Allow all the time\" is off and Ruby wasn't open."
                else -> ". Android refused location access: $locationError"
            }
        if (startMessage != lastStartMessage) {
            lastStartMessage = startMessage
            SensingLog.add(startMessage)
        }

        if (watchJob == null) {
            val settings = CarSettings(applicationContext)
            // After a reboot the saved state can be out of date, so ask Bluetooth directly.
            CarBluetooth.refreshSavedState(this, settings, scope)
            // Keep the notification text in step with the current state. Only the labels
            // are compared, so the notification doesn't redraw every 5 seconds.
            watchJob = scope.launch {
                combine(settings.car, ContextStatus.reading) { car, context ->
                    NotificationState(state = context?.state, carName = car?.name)
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

        // The decision engine (Phase 5) turns everything below into one current state.
        if (contextEngine == null) {
            contextEngine = ContextEngine(this, scope).also { it.start() }
        }

        // Start GPS sensing whenever precise location is allowed. This used to wait for the
        // service's "location" type to be accepted, and if Android refused that, GPS never
        // started and nothing said why. Now any problem shows up in the log when GPS is tried.
        if (GpsReader.hasFineLocation(this) && locationSensing == null) {
            locationSensing = LocationSensing(this, scope).also { it.start() }
        }

        if (intent?.action == BackgroundSensing.ACTION_TEST_GPS) {
            locationSensing?.testNow()
                ?: SensingLog.add("Test: GPS sensing isn't running because precise location isn't allowed.")
        }

        // If Android does stop the service to free memory, it restarts it when it can.
        return START_STICKY
    }

    override fun onDestroy() {
        motionSensor?.stop()
        locationSensing?.stop()
        contextEngine?.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Shows the ongoing notification and declares what the service uses.
     * Returns null if it worked, or Android's reason if it didn't.
     */
    private fun startInForeground(withLocation: Boolean): String? {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (withLocation) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(shown), types)
            null
        } catch (e: Exception) {
            // For example, the Bluetooth permission was turned off in Settings (Android 14+
            // requires it for this kind of service), or Android blocked a background start.
            "${e.javaClass.simpleName}: ${e.message}"
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
        /** The decision engine's state, or null before it has started. */
        val state: ContextState? = null,
        val carName: String? = null,
    )

    private fun buildNotification(state: NotificationState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val status = when {
            state.state == null -> "Starting…"
            state.state.kind == ContextState.Kind.DRIVING && state.carName != null ->
                "Driving (${state.carName})"
            else -> state.state.label
        }

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
