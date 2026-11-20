package com.martin.ruby.sensing

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.martin.ruby.bluetooth.CarBluetooth

/** Starts and stops the background service, and checks battery settings. */
object BackgroundSensing {

    /** Tells the service it was started while Ruby was on screen, so it may use GPS. */
    const val EXTRA_FROM_APP = "com.martin.ruby.FROM_APP"

    /**
     * Starts the service if Ruby has the Bluetooth permission it needs. Safe to call again.
     * Pass [fromApp] = true only when Ruby is on screen.
     */
    fun start(context: Context, fromApp: Boolean = false) {
        if (!CarBluetooth.hasPermission(context)) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SensingService::class.java).putExtra(EXTRA_FROM_APP, fromApp),
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
