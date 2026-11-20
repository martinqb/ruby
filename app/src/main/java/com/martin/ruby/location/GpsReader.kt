package com.martin.ruby.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat

/**
 * Reads the phone's GPS through Android's built-in LocationManager, not Google's
 * fused location service, so location never goes through Google Play services.
 */
class GpsReader(private val context: Context) {

    private val locationManager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListenerCompat? = null

    val isRunning: Boolean get() = listener != null

    /** False if Location is switched off in Android's settings. */
    fun isGpsOn(): Boolean {
        val manager = locationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager) &&
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /** Starts a GPS reading every second. Returns false if GPS can't be used right now. */
    @SuppressLint("MissingPermission") // checked by hasFineLocation()
    fun startUpdates(onFix: (Location) -> Unit): Boolean {
        val manager = locationManager ?: return false
        if (!hasFineLocation(context) || !isGpsOn()) return false
        stopUpdates()

        val newListener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) = onFix(location)
        }
        val request = LocationRequestCompat.Builder(1_000L)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        return try {
            LocationManagerCompat.requestLocationUpdates(
                manager, LocationManager.GPS_PROVIDER, request, newListener, Looper.getMainLooper()
            )
            listener = newListener
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission") // Stopping GPS doesn't need a permission.
    fun stopUpdates() {
        val current = listener ?: return
        locationManager?.let { LocationManagerCompat.removeUpdates(it, current) }
        listener = null
    }

    /**
     * Gets one fresh GPS fix, then turns GPS off again. Android gives up after about
     * 30 seconds (GPS can struggle indoors), and then [onResult] gets null.
     * Returns a signal that can cancel the request, or null if it couldn't start.
     */
    @SuppressLint("MissingPermission") // checked by hasFineLocation()
    fun singleFix(onResult: (Location?) -> Unit): CancellationSignal? {
        val manager = locationManager
        if (manager == null || !hasFineLocation(context) || !isGpsOn()) {
            onResult(null)
            return null
        }
        val cancel = CancellationSignal()
        return try {
            LocationManagerCompat.getCurrentLocation(
                manager,
                LocationManager.GPS_PROVIDER,
                cancel,
                ContextCompat.getMainExecutor(context),
            ) { location -> onResult(location) }
            cancel
        } catch (e: SecurityException) {
            onResult(null)
            null
        }
    }

    companion object {
        fun hasFineLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /** "Allow all the time": lets GPS work when Ruby wasn't opened since the phone restarted. */
        fun hasBackgroundLocation(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}
