package com.martin.ruby.listening

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.martin.ruby.R
import com.martin.ruby.log.SensingLog
import com.martin.ruby.ui.WakeActivity

/**
 * Bringing Ruby up on the wake word: the screen turns on and her small view (orb and the
 * conversation) shows over the lock screen ([WakeActivity]). She never unlocks the phone; tapping
 * her orb asks the user to unlock first.
 *
 * Android doesn't let an app open a screen from the background, except with "Display over other
 * apps" (asked once, in Voice settings). Without it, a full-screen notification does the same
 * when the phone is locked, and is an ordinary notification otherwise.
 */
object WakeScreen {

    enum class Route {
        /** Ruby (full screen or her small view) is already on screen: nothing to open. */
        ALREADY_SHOWN,
        /** "Display over other apps" is allowed: open her small view directly. */
        OPEN_VIEW,
        /** Otherwise: a full-screen notification opens it (or shows as a notification). */
        NOTIFICATION,
    }

    fun route(rubyShown: Boolean, canOpenFromBackground: Boolean): Route = when {
        rubyShown -> Route.ALREADY_SHOWN
        canOpenFromBackground -> Route.OPEN_VIEW
        else -> Route.NOTIFICATION
    }

    /** Ruby's full screen is in front (MainActivity resumed). Main thread. */
    @Volatile var mainShown = false

    /** Her small view is in front. Main thread. */
    @Volatile var viewShown = false

    fun canOpenFromBackground(context: Context) = Settings.canDrawOverlays(context)

    /** The settings page where the user allows "Display over other apps" for Ruby. */
    fun overlaySettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The wake word was heard: turn the screen on and show Ruby. */
    fun show(context: Context) {
        val app = context.applicationContext
        val route = route(mainShown || viewShown, canOpenFromBackground(app))
        SensingLog.add("Listening: showing Ruby (${route.name.lowercase().replace('_', ' ')}).")
        when (route) {
            Route.ALREADY_SHOWN -> Unit
            Route.OPEN_VIEW -> try {
                app.startActivity(viewIntent(app))
            } catch (e: Exception) {
                SensingLog.add("Listening: couldn't open Ruby's view (${e.javaClass.simpleName}); using a notification.")
                notify(app)
            }
            Route.NOTIFICATION -> notify(app)
        }
    }

    private fun viewIntent(context: Context) = Intent(context, WakeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

    private fun notify(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wake word", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows Ruby when you say her wake word with the screen off."
                setSound(null, null)
                enableVibration(false)
            }
        )
        if (Build.VERSION.SDK_INT >= 34 && !manager.canUseFullScreenIntent()) {
            SensingLog.add("Listening: full-screen notifications aren't allowed for Ruby; showing an ordinary one.")
        }
        val open = PendingIntent.getActivity(context, 2, viewIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ruby is listening")
            .setContentText("Tap to see her.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            SensingLog.add("Listening: can't post notifications, so Ruby can't show herself.")
        }
    }

    /** Her view is showing: the notification that opened it isn't needed. */
    fun clearNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /**
     * Whether her view keeps the screen on: while the conversation is going (she's listening,
     * thinking or talking), and for [GRACE_MS] after, so the gap between turns doesn't let it go
     * dark. After that the screen times out as usual.
     */
    fun keepScreenOn(active: Boolean, idleForMs: Long): Boolean = active || idleForMs < GRACE_MS

    const val GRACE_MS = 5_000L
    private const val CHANNEL_ID = "wake"
    private const val NOTIFICATION_ID = 8
    private const val NOTIFICATION_TIMEOUT_MS = 60_000L
}
