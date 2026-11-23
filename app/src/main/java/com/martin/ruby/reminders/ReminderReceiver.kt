package com.martin.ruby.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A reminder's alarm going off, its notification's Done and Snooze buttons, and Android
 * granting exact alarms (then waiting reminders are scheduled again, exactly).
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val store = ReminderStore(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> if (id >= 0) store.fire(id)
                    ACTION_DONE -> if (id >= 0) store.done(id)
                    ACTION_SNOOZE -> if (id >= 0) store.snooze(id)
                    AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> store.rescheduleAll()
                }
            } catch (e: Exception) {
                SensingLog.add("Reminder $id: ${intent.action?.substringAfterLast('.')} failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.martin.ruby.REMINDER_FIRE"
        const val ACTION_DONE = "com.martin.ruby.REMINDER_DONE"
        const val ACTION_SNOOZE = "com.martin.ruby.REMINDER_SNOOZE"
        const val EXTRA_ID = "reminder_id"
    }
}
