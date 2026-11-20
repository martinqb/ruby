package com.martin.ruby

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.martin.ruby.bluetooth.CarBluetooth
import com.martin.ruby.data.CarSettings
import com.martin.ruby.ui.CarPickerScreen
import com.martin.ruby.ui.MainScreen
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settings: CarSettings

    // Compose redraws the screen whenever this changes.
    private var hasBluetoothPermission by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasBluetoothPermission = granted
            refreshCarState()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CarSettings(applicationContext)
        hasBluetoothPermission = CarBluetooth.hasPermission(this)

        // Ask the first time the app opens.
        if (savedInstanceState == null && !hasBluetoothPermission) requestBluetoothPermission()

        enableEdgeToEdge()
        setContent {
            RubyTheme {
                val car by settings.car.collectAsState(initial = null)
                val carConnected by settings.carConnected.collectAsState(initial = false)
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
                            onRequestPermission = ::requestBluetoothPermission,
                            onChooseCar = { showCarPicker = true },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the permission in Settings, or the car may have
        // connected while Ruby was closed, so check again whenever the screen comes back.
        hasBluetoothPermission = CarBluetooth.hasPermission(this)
        refreshCarState()
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Asks Android whether the saved car is connected right now and saves the answer. */
    private fun refreshCarState() {
        lifecycleScope.launch {
            val car = settings.car.first() ?: return@launch
            CarBluetooth.checkConnectedNow(this@MainActivity, car.address) { connected ->
                lifecycleScope.launch { settings.setCarConnected(connected) }
            }
        }
    }
}
