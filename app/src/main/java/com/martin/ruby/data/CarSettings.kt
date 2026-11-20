package com.martin.ruby.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A Bluetooth device the user picked as their car. */
data class Car(val address: String, val name: String)

// One DataStore file for the whole app. It must be declared once, at the top level.
private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Saves which device is the car, and whether it's connected right now.
 * Both the screen and the background Bluetooth receiver use this, so they always agree.
 */
class CarSettings(private val context: Context) {

    private object Keys {
        val CAR_ADDRESS = stringPreferencesKey("car_address")
        val CAR_NAME = stringPreferencesKey("car_name")
        val CAR_CONNECTED = booleanPreferencesKey("car_connected")
    }

    /** The chosen car, or null if the user hasn't picked one yet. */
    val car: Flow<Car?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CAR_ADDRESS]?.let { address -> Car(address, prefs[Keys.CAR_NAME] ?: address) }
    }

    /** True while the phone is connected to the car's Bluetooth. */
    val carConnected: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.CAR_CONNECTED] ?: false
    }

    suspend fun saveCar(car: Car) {
        context.dataStore.edit { prefs ->
            prefs[Keys.CAR_ADDRESS] = car.address
            prefs[Keys.CAR_NAME] = car.name
            // A new car hasn't been seen connecting yet; MainActivity re-checks right after.
            prefs[Keys.CAR_CONNECTED] = false
        }
    }

    suspend fun setCarConnected(connected: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.CAR_CONNECTED] = connected }
    }
}
