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
import com.martin.ruby.backup.BackupManager
import com.martin.ruby.backup.BackupStatusStore
import com.martin.ruby.backup.NightlyBackup
import com.martin.ruby.bluetooth.CarBluetooth
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.data.CarSettings
import com.martin.ruby.data.Place
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.decision.ContextStatus
import com.martin.ruby.location.GpsReader
import com.martin.ruby.location.LocationStatus
import com.martin.ruby.log.SensingLog
import com.martin.ruby.memory.MemoryStore
import com.martin.ruby.motion.MotionSensor
import com.martin.ruby.motion.MotionStatus
import com.martin.ruby.sensing.BackgroundSensing
import com.martin.ruby.ui.BackupScreen
import com.martin.ruby.ui.CarPickerScreen
import com.martin.ruby.ui.ChatActions
import com.martin.ruby.ui.ChatScreen
import com.martin.ruby.ui.MainActions
import com.martin.ruby.ui.MainScreen
import com.martin.ruby.ui.MainUiState
import com.martin.ruby.ui.MemoryScreen
import com.martin.ruby.ui.PlacesScreen
import com.martin.ruby.ui.ExportScreen
import com.martin.ruby.ui.StorageScreen
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen is showing. */
private enum class Screen { MAIN, CHAT, CAR_PICKER, PLACES, MEMORY, BACKUP, STORAGE, EXPORT }

class MainActivity : ComponentActivity() {

    private lateinit var settings: CarSettings
    private lateinit var placeStore: PlaceStore
    private lateinit var memoryStore: MemoryStore
    private lateinit var backupManager: BackupManager

    // Compose redraws the screen whenever these change.
    private var hasBluetoothPermission by mutableStateOf(false)
    private var hasActivityPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)
    private var hasBackgroundLocation by mutableStateOf(false)
    private var gpsEnabled by mutableStateOf(true)
    private var batteryOptimized by mutableStateOf(false)
    private var savingPlace by mutableStateOf(false)
    private var placeMessage by mutableStateOf<String?>(null)

    /** Set when a notification (e.g. "approve Drive access again") asks for the Backup screen. */
    private var openBackupRequested by mutableStateOf(false)

    /** Set when the "memory is full" notification asks for the Storage screen. */
    private var openStorageRequested by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermissionsChanged()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = CarSettings(applicationContext)
        placeStore = PlaceStore(applicationContext)
        memoryStore = MemoryStore(applicationContext)
        backupManager = BackupManager(applicationContext)
        openBackupRequested = intent?.getBooleanExtra(EXTRA_OPEN_BACKUP, false) == true
        openStorageRequested = intent?.getBooleanExtra(EXTRA_OPEN_STORAGE, false) == true
        checkPermissions()
        ChatSession.checkKey(applicationContext)

        // Android's own backup can bring "nightly is on" to a new phone without the
        // scheduled job, so make sure it's scheduled. Scheduling again changes nothing.
        lifecycleScope.launch {
            if (BackupStatusStore(applicationContext).current().nightlyOn && backupManager.isSetUp()) {
                NightlyBackup.schedule(applicationContext)
            }
        }

        // Ask the first time the app opens (or the first time after an update adds a permission).
        // Location is asked for separately, when the user taps "Allow location".
        if (savedInstanceState == null && (!hasBluetoothPermission || !hasActivityPermission)) {
            requestPermissions()
        }

        enableEdgeToEdge()
        setContent {
            RubyTheme {
                val car by settings.car.collectAsState(initial = null)
                val carConnected by settings.carConnected.collectAsState(initial = false)
                val backgroundSensing by settings.backgroundSensing.collectAsState(initial = true)
                val motion by MotionStatus.reading.collectAsState()
                val context by ContextStatus.reading.collectAsState()
                val location by LocationStatus.reading.collectAsState()
                val places by placeStore.places.collectAsState(initial = emptyList())
                val log by SensingLog.entries.collectAsState()
                val chat by ChatSession.ui.collectAsState()
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                if (openStorageRequested) {
                    openStorageRequested = false
                    screen = Screen.STORAGE
                }
                if (openBackupRequested) {
                    openBackupRequested = false
                    screen = Screen.BACKUP
                }

                if (screen != Screen.MAIN) BackHandler {
                    screen = if (screen == Screen.BACKUP || screen == Screen.STORAGE || screen == Screen.EXPORT) Screen.MEMORY else Screen.MAIN
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val modifier = Modifier.padding(innerPadding)
                    when (screen) {
                        Screen.CAR_PICKER -> {
                            val devices = remember { CarBluetooth.pairedDevices(this) }
                            CarPickerScreen(
                                devices = devices,
                                selectedAddress = car?.address,
                                onPick = { picked ->
                                    lifecycleScope.launch {
                                        settings.saveCar(picked)
                                        refreshCarState()
                                    }
                                    screen = Screen.MAIN
                                },
                                onBack = { screen = Screen.MAIN },
                                modifier = modifier,
                            )
                        }

                        Screen.CHAT -> ChatScreen(
                            ui = chat,
                            context = context?.state?.label,
                            actions = ChatActions(
                                onSend = { text -> ChatSession.send(applicationContext, text) },
                                onSaveKey = { key -> ChatSession.saveKey(applicationContext, key) },
                                onForgetKey = { ChatSession.forgetKey(applicationContext) },
                                onNewConversation = ChatSession::newConversation,
                                onDismissError = ChatSession::dismissError,
                                onBack = { screen = Screen.MAIN },
                            ),
                            modifier = modifier,
                        )

                        Screen.MEMORY -> MemoryScreen(
                            store = memoryStore,
                            onOpenBackup = { screen = Screen.BACKUP },
                            onOpenStorage = { screen = Screen.STORAGE },
                            onOpenExport = { screen = Screen.EXPORT },
                            onBack = { screen = Screen.MAIN },
                            modifier = modifier,
                        )

                        Screen.BACKUP -> BackupScreen(
                            manager = backupManager,
                            onBack = { screen = Screen.MEMORY },
                            modifier = modifier,
                        )

                        Screen.STORAGE -> StorageScreen(
                            store = memoryStore,
                            onBack = { screen = Screen.MEMORY },
                            modifier = modifier,
                        )

                        Screen.EXPORT -> ExportScreen(
                            store = memoryStore,
                            onBack = { screen = Screen.MEMORY },
                            modifier = modifier,
                        )

                        Screen.PLACES -> PlacesScreen(
                            places = places,
                            currentPlace = location.place,
                            saving = savingPlace,
                            message = placeMessage,
                            onSave = ::saveCurrentPlace,
                            onDelete = { place -> lifecycleScope.launch { placeStore.remove(place) } },
                            onBack = {
                                placeMessage = null
                                screen = Screen.MAIN
                            },
                            modifier = modifier,
                        )

                        Screen.MAIN -> MainScreen(
                            state = MainUiState(
                                car = car,
                                driving = car != null && carConnected,
                                context = context,
                                motion = motion,
                                location = location,
                                savedPlaceCount = places.size,
                                hasBluetoothPermission = hasBluetoothPermission,
                                hasActivityPermission = hasActivityPermission,
                                hasLocationPermission = hasLocationPermission,
                                hasBackgroundLocation = hasBackgroundLocation,
                                gpsEnabled = gpsEnabled,
                                backgroundSensing = backgroundSensing,
                                batteryOptimized = batteryOptimized,
                                log = log,
                            ),
                            actions = MainActions(
                                onRequestPermissions = ::requestPermissions,
                                onChooseCar = { screen = Screen.CAR_PICKER },
                                onBackgroundSensingChange = ::setBackgroundSensing,
                                onAllowBackground = ::askToIgnoreBatteryOptimization,
                                onAllowLocation = ::requestLocation,
                                onAllowLocationAllTheTime = ::requestBackgroundLocation,
                                onOpenPlaces = { screen = Screen.PLACES },
                                onOpenChat = { screen = Screen.CHAT },
                                onOpenMemory = { screen = Screen.MEMORY },
                                onTestGps = { BackgroundSensing.testGps(this) },
                                onClearLog = SensingLog::clear,
                            ),
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_BACKUP, false)) openBackupRequested = true
        if (intent.getBooleanExtra(EXTRA_OPEN_STORAGE, false)) openStorageRequested = true
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed a permission or setting, or the car may have
        // connected while Ruby was closed, so check again whenever the screen comes back.
        checkPermissions()
        refreshCarState()
        startSensingIfEnabled()
    }

    private fun checkPermissions() {
        hasBluetoothPermission = CarBluetooth.hasPermission(this)
        hasActivityPermission = MotionSensor.hasActivityPermission(this)
        hasLocationPermission = GpsReader.hasFineLocation(this)
        hasBackgroundLocation = GpsReader.hasBackgroundLocation(this)
        gpsEnabled = GpsReader(this).isGpsOn()
        batteryOptimized = BackgroundSensing.isBatteryOptimized(this)
    }

    private fun onPermissionsChanged() {
        checkPermissions()
        refreshCarState()
        // Restart so the service picks up new permissions (like starting GPS).
        startSensingIfEnabled()
    }

    /** Physical activity, Bluetooth (Android 12+) and notifications (Android 13+), asked together. */
    private fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /** Precise location, for GPS. Android asks for both precise and approximate together. */
    private fun requestLocation() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    /**
     * "Allow all the time". Android asks for this separately, after normal location, and on
     * Android 11+ it takes the user to Ruby's location settings page to choose it.
     */
    private fun requestBackgroundLocation() {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private fun refreshCarState() {
        CarBluetooth.refreshSavedState(this, settings, lifecycleScope)
    }

    private fun startSensingIfEnabled() {
        lifecycleScope.launch {
            if (settings.backgroundSensing.first()) {
                BackgroundSensing.start(this@MainActivity, fromApp = true)
            }
        }
    }

    private fun setBackgroundSensing(enabled: Boolean) {
        lifecycleScope.launch {
            settings.setBackgroundSensing(enabled)
            if (enabled) BackgroundSensing.start(this@MainActivity, fromApp = true)
            else BackgroundSensing.stop(this@MainActivity)
        }
    }

    /** Takes one GPS fix and saves it under [name]. */
    private fun saveCurrentPlace(name: String) {
        val gps = GpsReader(this)
        if (!gps.isGpsOn()) {
            placeMessage = "Location is turned off. Turn it on in Android's settings first."
            return
        }
        savingPlace = true
        placeMessage = "Finding your location. This can take up to 30 seconds; it's quicker " +
            "near a window or outside."
        gps.singleFix { location ->
            savingPlace = false
            if (location == null) {
                placeMessage = "Couldn't get a GPS fix. Try again near a window or outside."
                return@singleFix
            }
            lifecycleScope.launch {
                placeStore.add(Place(name, location.latitude, location.longitude))
            }
            val accuracy = if (location.hasAccuracy()) " (accurate to about %.0f m)".format(location.accuracy) else ""
            placeMessage = "Saved $name$accuracy."
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

    companion object {
        /** Intent extra: open straight to the Backup screen. */
        const val EXTRA_OPEN_BACKUP = "com.martin.ruby.OPEN_BACKUP"

        /** Intent extra: open straight to the memory Storage screen. */
        const val EXTRA_OPEN_STORAGE = "com.martin.ruby.OPEN_STORAGE"
    }
}
