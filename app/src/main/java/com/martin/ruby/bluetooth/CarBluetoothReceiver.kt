package com.martin.ruby.bluetooth

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import com.martin.ruby.data.CarSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android calls this whenever any Bluetooth device connects or disconnects.
 * If it's the user's car, we save the new state, and the main screen updates by itself.
 *
 * It's declared in AndroidManifest.xml, so it runs even when the app isn't open.
 * Only the system can send these broadcasts, so other apps can't fake them.
 */
class CarBluetoothReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return

        // Saving takes a moment, so tell Android we're still working, then finish when done.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = CarSettings(context.applicationContext)
                val car = settings.car.first()
                if (car != null && device.address == car.address) {
                    settings.setCarConnected(connected)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
