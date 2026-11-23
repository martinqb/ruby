package com.martin.ruby.sensing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.martin.ruby.data.CarSettings
import com.martin.ruby.reminders.ReminderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts background sensing after the phone restarts, and after Ruby is updated
 * (installing a new version stops the old service). Does nothing if the user turned
 * background sensing off. Also sets reminders' alarms again, which Android forgets.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val appContext = context.applicationContext
                // Android forgets alarms on restart, so set reminders' alarms again.
                runCatching { ReminderStore(appContext).rescheduleAll() }
                if (CarSettings(appContext).backgroundSensing.first()) {
                    BackgroundSensing.start(appContext)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
