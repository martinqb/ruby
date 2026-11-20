package com.martin.ruby.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.martin.ruby.data.Car
import com.martin.ruby.data.CarSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Small helpers for the Bluetooth calls the app needs. */
object CarBluetooth {

    /** BLUETOOTH_CONNECT is a runtime permission on Android 12+; older versions grant Bluetooth at install. */
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Devices already paired with this phone, for the "choose your car" list. */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun pairedDevices(context: Context): List<Car> {
        if (!hasPermission(context)) return emptyList()
        val adapter = adapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices
                .map { Car(address = it.address, name = it.name ?: it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Checks whether the saved car is connected right now and saves the answer.
     * [scope] must run on the main thread.
     */
    fun refreshSavedState(context: Context, settings: CarSettings, scope: CoroutineScope) {
        scope.launch {
            val car = settings.car.first() ?: return@launch
            checkConnectedNow(context, car.address) { connected ->
                scope.launch { settings.setCarConnected(connected) }
            }
        }
    }

    /**
     * Asks Android whether the car is connected right now.
     *
     * The receiver only hears about changes, so this covers the case where the car was
     * already connected before the app started. Cars use the audio (A2DP) or phone call
     * (HEADSET) profiles, so we check both. [onResult] isn't called if we can't tell.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission()
    fun checkConnectedNow(context: Context, carAddress: String, onResult: (Boolean) -> Unit) {
        if (!hasPermission(context)) return
        val adapter = adapter(context) ?: return
        if (!adapter.isEnabled) {
            onResult(false) // Bluetooth is off, so the car can't be connected.
            return
        }

        val profiles = listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        var waitingFor = profiles.size
        var found = false

        fun oneProfileDone() {
            waitingFor--
            if (waitingFor == 0) onResult(found)
        }

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    if (proxy.connectedDevices.any { it.address == carAddress }) found = true
                } catch (e: SecurityException) {
                    // Permission was revoked while we were asking; treat as not found.
                }
                adapter.closeProfileProxy(profile, proxy)
                oneProfileDone()
            }

            override fun onServiceDisconnected(profile: Int) {}
        }

        // The answers arrive later on the main thread, after this loop finishes.
        profiles.forEach { profile ->
            if (!adapter.getProfileProxy(context, listener, profile)) oneProfileDone()
        }
    }
}
