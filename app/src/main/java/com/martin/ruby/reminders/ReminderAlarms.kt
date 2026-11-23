package com.martin.ruby.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Wakes the phone when a time reminder is due, using Android's alarm scheduler.
 *
 * Exact timing needs the "Alarms & reminders" permission on Android 12+, which the user
 * turns on in Settings (the Reminders screen links to it). Without it, the alarm is
 * inexact and can be a few minutes late while the phone is idle.
 */
object ReminderAlarms {

    fun canBeExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, reminder: Reminder) {
        val due = reminder.dueMillis ?: return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = fireIntent(context, reminder.id)
        if (canBeExact(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, intent)
        }
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(fireIntent(context, id))
    }

    private fun fireIntent(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
