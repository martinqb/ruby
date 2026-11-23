package com.martin.ruby.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.martin.ruby.MainActivity
import com.martin.ruby.R

/** The notification a reminder shows, with Done and (for time reminders) Snooze. */
object ReminderNotifier {

    private const val CHANNEL_ID = "reminders"

    /** Notification ids for reminders start here, so they don't clash with Ruby's others. */
    private const val ID_BASE = 100_000

    const val SNOOZE_MINUTES = 10

    fun show(context: Context, reminder: Reminder, late: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return // the Reminders screen still shows it
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders you asked Ruby for."
            }
        )
        val title = when {
            reminder.place != null -> "Reminder: you're at ${reminder.place}"
            late -> "Reminder (missed while the phone was off)"
            else -> "Reminder"
        }
        val open = PendingIntent.getActivity(
            context,
            notificationId(reminder.id),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_REMINDERS, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Its own group, so Android doesn't bundle it under "Context sensing on", where
            // Done and Snooze only show after opening the bundle and then the reminder.
            .setGroup("reminder-${reminder.id}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", action(context, reminder.id, ReminderReceiver.ACTION_DONE))
        if (reminder.dueMillis != null) {
            builder.addAction(0, "Snooze $SNOOZE_MINUTES min", action(context, reminder.id, ReminderReceiver.ACTION_SNOOZE))
        }
        manager.notify(notificationId(reminder.id), builder.build())
    }

    fun dismiss(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
    }

    private fun action(context: Context, id: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        // Different request codes per action, or Android would reuse one PendingIntent for both.
        notificationId(id) * 4 + if (action == ReminderReceiver.ACTION_DONE) 1 else 2,
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificationId(id: Long) = ID_BASE + id.toInt()
}
