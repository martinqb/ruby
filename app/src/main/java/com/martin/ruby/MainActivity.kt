package com.martin.ruby

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.martin.ruby.bluetooth.CarBluetooth
import com.martin.ruby.data.CarSettings
import com.martin.ruby.sensing.BackgroundSensing
import com.martin.ruby.ui.CarPickerScreen
import com.martin.ruby.ui.MainScreen
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: CarSettings

    // Compose redraws the screen whenever these change.
    private var hasBluetoothPermission by mutableStateOf(false)
    private var batteryOptimized by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasBluetoothPermission = CarBluetooth.hasPermission(this)
            refreshCarState()
            startSensingIfEnabled()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CarSettings(applicationContext)
        hasBluetoothPermission = CarBluetooth.hasPermission(this)

        // Ask the first time the app opens.
        if (savedInstanceState == null && !hasBluetoothPermission) requestPermissions()

        enableEdgeToEdge()
        setContent {
            RubyTheme {
                val car by settings.car.collectAsState(initial = null)
                val carConnected by settings.carConnected.collectAsState(initial = false)
                val backgroundSensing by settings.backgroundSensing.collectAsState(initial = true)
                var showCarPicker by rememberSaveable { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (showCarPicker) {
                        BackHandler { showCarPicker = false }
                        val devices = remember { CarBluetooth.pairedDevices(this) }
                        CarPickerScreen(
                            devices = devices,
                            selectedAddress = car?.address,
                            onPick = { picked ->
                                lifecycleScope.launch {
                                    settings.saveCar(picked)
                                    refreshCarState()
                                }
                                showCarPicker = false
                            },
                            onBack = { showCarPicker = false },
                            modifier = Modifier.padding(innerPadding),
                        )
                    } else {
                        MainScreen(
                            car = car,
                            driving = car != null && carConnected,
                            hasBluetoothPermission = hasBluetoothPermission,
                            onRequestPermission = ::requestPermissions,
                            onChooseCar = { showCarPicker = true },
                            backgroundSensing = backgroundSensing,
                            onBackgroundSensingChange = ::setBackgroundSensing,
                            batteryOptimized = batteryOptimized,
                            onAllowBackground = ::askToIgnoreBatteryOptimization,
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed a permission or battery setting, or the car may have
        // connected while Ruby was closed, so check again whenever the screen comes back.
        hasBluetoothPermission = CarBluetooth.hasPermission(this)
        batteryOptimized = BackgroundSensing.isBatteryOptimized(this)
        refreshCarState()
        startSensingIfEnabled()
    }

    /** Bluetooth (Android 12+) and notifications (Android 13+), asked together. */
    private fun requestPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun refreshCarState() {
        CarBluetooth.refreshSavedState(this, settings, lifecycleScope)
    }

    private fun startSensingIfEnabled() {
        lifecycleScope.launch {
            if (settings.backgroundSensing.first()) BackgroundSensing.start(this@MainActivity)
        }
    }

    private fun setBackgroundSensing(enabled: Boolean) {
        lifecycleScope.launch {
            settings.setBackgroundSensing(enabled)
            if (enabled) BackgroundSensing.start(this@MainActivity)
            else BackgroundSensing.stop(this@MainActivity)
        }
    }

    /** Shows Android's "Let Ruby run in the background?" dialog. */
    @SuppressLint("BatteryLife") // Ruby needs to run all day; that's the point of the app.
    private fun askToIgnoreBatteryOptimization() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:$packageName".toUri(),
                )
            )
        } catch (e: ActivityNotFoundException) {
            // Some phones don't have the dialog; open the battery settings list instead.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
