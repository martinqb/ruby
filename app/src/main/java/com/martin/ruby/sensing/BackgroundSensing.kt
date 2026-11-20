package com.martin.ruby.sensing

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.martin.ruby.bluetooth.CarBluetooth

/** Starts and stops the background service, and checks battery settings. */
object BackgroundSensing {

    /** Starts the service if Ruby has the Bluetooth permission it needs. Safe to call again. */
    fun start(context: Context) {
        if (!CarBluetooth.hasPermission(context)) return
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, SensingService::class.java)
            )
        } catch (e: IllegalStateException) {
            // Android 12+ blocks starting from the background in some cases. The service
            // will start the next time Ruby is opened.
        }
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, SensingService::class.java))
    }

    /** True while Android may pause or kill Ruby to save battery. */
    fun isBatteryOptimized(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return !power.isIgnoringBatteryOptimizations(context.packageName)
    }
}
