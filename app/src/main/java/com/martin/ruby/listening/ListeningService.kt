package com.martin.ruby.listening

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lets the listening modes keep the mic while Ruby is in the background, with an ongoing
 * "Ruby is listening" notification and its Turn off button (Android shows its own mic
 * indicator too). The listening itself is in [Listening]; this only keeps it allowed.
 *
 * Android only lets an app start a microphone service while it's on screen, so it's started
 * when Ruby is opened or the settings change there, not after a reboot.
 */
class ListeningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when Ruby is listening for you in the background."
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OFF) {
            Listening.setOn(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(ListeningPrefs()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            // No mic permission, or Android refused a start from the background.
            SensingLog.add("Listening: background listening couldn't start (${e.javaClass.simpleName}).")
            stopSelf()
            return START_NOT_STICKY
        }
        Listening.start(this)
        if (watch == null) {
            watch = scope.launch {
                ListeningSettings(this@ListeningService).prefs.collect { prefs ->
                    if (!prefs.active) {
                        stopSelf()
                    } else if (canNotify()) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(prefs))
                    }
                }
            }
        }
        // Not restarted by Android: it couldn't use the mic from the background anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun canNotify() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notification(prefs: ListeningPrefs): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(
            this, 1, Intent(this, ListeningService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ruby is listening")
            .setContentText(if (prefs.anyMode) ListeningPlan.notification(prefs) else "Starting…")
            .setGroup("listening")
            .setContentIntent(open)
            .addAction(0, "Turn off", off)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_OFF = "com.martin.ruby.listening.OFF"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ListeningService::class.java))
            } catch (e: Exception) {
                SensingLog.add("Listening: background listening couldn't start (${e.javaClass.simpleName}).")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListeningService::class.java))
        }
    }
}
