package com.martin.ruby

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.ai.AiSettings
import com.martin.ruby.ai.FreeMode
import com.martin.ruby.backup.BackupManager
import com.martin.ruby.radio.OtherRadioApps
import com.martin.ruby.radio.PresetStore
import com.martin.ruby.radio.Presets
import com.martin.ruby.radio.Radio
import com.martin.ruby.radio.RadioSearch
import com.martin.ruby.ui.RadioScreen
import com.martin.ruby.radio.RadioSettings
import com.martin.ruby.ui.RadioActions
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
import com.martin.ruby.briefing.BriefingState
import com.martin.ruby.briefing.CalendarReader
import com.martin.ruby.briefing.MorningBriefing
import com.martin.ruby.ui.BackupScreen
import com.martin.ruby.ui.BriefingActions
import com.martin.ruby.ui.BriefingScreen
import com.martin.ruby.ui.BriefingSetup
import com.martin.ruby.ui.CarPickerScreen
import com.martin.ruby.ui.ChatActions
import com.martin.ruby.ui.ChatScreen
import com.martin.ruby.ui.MainActions
import com.martin.ruby.ui.MainScreen
import com.martin.ruby.ui.MainUiState
import com.martin.ruby.ui.MemoryScreen
import com.martin.ruby.ui.PlacesScreen
import com.martin.ruby.ui.RemindersScreen
import com.martin.ruby.ui.RubyUi
import com.martin.ruby.ui.SummariesScreen
import com.martin.ruby.memory.Summaries
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.ui.ExportScreen
import com.martin.ruby.ui.StorageScreen
import com.martin.ruby.ui.theme.RubyTheme
import com.martin.ruby.listening.Listening
import com.martin.ruby.listening.VoskModel
import com.martin.ruby.ui.VoiceSettingsActions
import com.martin.ruby.ui.VoiceSettingsScreen
import com.martin.ruby.voice.KokoroModel
import com.martin.ruby.voice.VoiceSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen is showing. */
private enum class Screen { MAIN, CHAT, REMINDERS, SUMMARIES, CAR_PICKER, PLACES, MEMORY, BACKUP, STORAGE, EXPORT, BRIEFING, RADIO, VOICE_SETTINGS }

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
    private var hasMicPermission by mutableStateOf(false)
    private var hasCalendarPermission by mutableStateOf(false)
    private var gpsEnabled by mutableStateOf(true)
    private var batteryOptimized by mutableStateOf(false)
    private var savingPlace by mutableStateOf(false)
    private var placeMessage by mutableStateOf<String?>(null)

    /** Set when a notification (e.g. "approve Drive access again") asks for the Backup screen. */
    private var openBackupRequested by mutableStateOf(false)

    /** Set when the "memory is full" notification asks for the Storage screen. */
    private var openStorageRequested by mutableStateOf(false)

    /** Set when a reminder's notification is tapped. */
    private var openRemindersRequested by mutableStateOf(false)

    /** Asked the first time the user taps Talk; if they allow it, Ruby starts listening. */
    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            if (granted) listen()
        }

    /** Asked on the Voice settings page, for the listening modes (doesn't start listening by itself). */
    private val modesMicLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasMicPermission = granted
            if (granted) Listening.appShown(this)
        }

    /** Asked when the briefing (with today's calendar) is turned on. */
    private val calendarLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCalendarPermission = granted
            SensingLog.add("Briefing: calendar access ${if (granted) "allowed" else "not allowed"}.")
        }

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
        openRemindersRequested = intent?.getBooleanExtra(EXTRA_OPEN_REMINDERS, false) == true
        checkPermissions()
        ChatSession.checkKey(applicationContext)
        VoiceSession.start(applicationContext)
        Listening.start(applicationContext)

        // Android's own backup can bring "nightly is on" to a new phone without the
        // scheduled job, so make sure it's scheduled. Scheduling again changes nothing.
        lifecycleScope.launch {
            if (BackupStatusStore(applicationContext).current().nightlyOn && backupManager.isSetUp()) {
                NightlyBackup.schedule(applicationContext)
            }
            // The same for nightly summaries (on unless the user turned them off).
            Summaries.ensureScheduled(applicationContext)
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
                val voice by VoiceSession.ui.collectAsState()
                val briefing by remember { MorningBriefing.state(applicationContext) }.collectAsState(initial = BriefingState())
                val briefingWorking by MorningBriefing.working.collectAsState()
                val radio by Radio.state.collectAsState()
                val listening by Listening.ui.collectAsState()
                val radioAfterBriefing by remember { RadioSettings(applicationContext).startAfterBriefing }.collectAsState(initial = false)
                val radioPresets by remember { PresetStore(applicationContext).presets }.collectAsState(initial = Presets())
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                val radioActions = remember {
                    RadioActions(
                        onPlay = { Radio.play(applicationContext, it) },
                        onStop = { Radio.stop(applicationContext) },
                        onOpenInIHeart = { Radio.openInIHeart(applicationContext, it) },
                        onOpenInOtherApp = { OtherRadioApps.open(applicationContext, it) },
                        onVisible = { Radio.refreshOtherApps(applicationContext) },
                        onAfterBriefing = { on -> lifecycleScope.launch { RadioSettings(applicationContext).setStartAfterBriefing(on) } },
                        onPresets = { change -> lifecycleScope.launch { PresetStore(applicationContext).update(change) } },
                        search = RadioSearch::search,
                        onBack = { screen = Screen.MAIN },
                    )
                }
                if (openStorageRequested) {
                    openStorageRequested = false
                    screen = Screen.STORAGE
                }
                if (openRemindersRequested) {
                    openRemindersRequested = false
                    screen = Screen.REMINDERS
                }
                if (openBackupRequested) {
                    openBackupRequested = false
                    screen = Screen.BACKUP
                }

                if (screen != Screen.MAIN) BackHandler {
                    screen = if (screen == Screen.BACKUP || screen == Screen.STORAGE || screen == Screen.EXPORT ||
                        screen == Screen.SUMMARIES
                    ) Screen.MEMORY else Screen.MAIN
                }

                // The same on the chat screen and the main screen's chat box.
                val chatActions = remember {
                    ChatActions(
                        onSend = { text -> ChatSession.send(applicationContext, text) },
                        onSaveKey = { key -> ChatSession.saveKey(applicationContext, key) },
                        onForgetKey = { ChatSession.forgetKey(applicationContext) },
                        onNewConversation = ChatSession::newConversation,
                        onDismissError = ChatSession::dismissError,
                        onTalk = ::listen,
                        onAllowMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onDoneTalking = VoiceSession::finishListening,
                        onCancelTalking = VoiceSession::cancelListening,
                        onDismissNote = VoiceSession::dismissNote,
                        onPlay = VoiceSession::play,
                        onStopSpeaking = VoiceSession::stopSpeaking,
                        onSaveVoice = { prefs -> VoiceSession.save(applicationContext, prefs) },
                        onTestVoice = {
                            VoiceSession.play("Hello. I'm Ruby. This is how I sound right now.")
                        },
                        onDownloadKokoro = { KokoroModel.download(applicationContext) },
                        onCancelKokoro = { KokoroModel.cancelDownload(applicationContext) },
                        onChooseEngine = { engine ->
                            lifecycleScope.launch {
                                AiSettings(applicationContext).save(engine)
                                SensingLog.add("AI engine: ${engine.label}.")
                                if (engine == AiEngine.FREE) FreeMode.refresh()
                            }
                        },
                        onCheckFreeMode = { lifecycleScope.launch { FreeMode.refresh() } },
                        onDownloadFree = { lifecycleScope.launch { FreeMode.download() } },
                        onUseClaude = { ChatSession.useClaudeForOffer(applicationContext) },
                        onDeclineOffer = ChatSession::declineOffer,
                    )
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
                            voice = voice,
                            hasMicPermission = hasMicPermission,
                            context = context?.state?.label,
                            actions = chatActions.copy(onBack = { screen = Screen.MAIN }),
                            modifier = modifier,
                        )

                        Screen.VOICE_SETTINGS -> VoiceSettingsScreen(
                            state = listening,
                            hasMic = hasMicPermission,
                            actions = VoiceSettingsActions(
                                onSave = { Listening.save(applicationContext, it) },
                                onDownloadModel = { VoskModel.download(applicationContext) },
                                onAllowMic = { modesMicLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                                unknownWords = { Listening.unknownWords(applicationContext, it) },
                                onBack = { screen = Screen.MAIN },
                            ),
                            modifier = modifier,
                        )

                        Screen.RADIO -> RadioScreen(
                            state = radio,
                            presets = radioPresets,
                            afterBriefing = radioAfterBriefing,
                            actions = radioActions,
                            modifier = modifier,
                        )

                        Screen.BRIEFING -> BriefingScreen(
                            state = briefing,
                            setup = BriefingSetup(
                                hasCar = car != null,
                                hasHome = places.any { it.name.equals("home", ignoreCase = true) },
                                hasCalendar = hasCalendarPermission,
                                working = briefingWorking,
                                speaking = voice.speaking,
                            ),
                            actions = BriefingActions(
                                onEnable = { MorningBriefing.setEnabled(applicationContext, it) },
                                onProfile = { MorningBriefing.saveProfile(applicationContext, it) },
                                onHearNow = { MorningBriefing.speakNow(applicationContext) },
                                onStopTalking = MorningBriefing::stop,
                                onAllowCalendar = { calendarLauncher.launch(Manifest.permission.READ_CALENDAR) },
                                onBack = { screen = Screen.MAIN },
                            ),
                            modifier = modifier,
                        )

                        Screen.REMINDERS -> RemindersScreen(
                            store = remember { ReminderStore(applicationContext) },
                            onBack = { screen = Screen.MAIN },
                            modifier = modifier,
                        )

                        Screen.MEMORY -> MemoryScreen(
                            store = memoryStore,
                            onOpenBackup = { screen = Screen.BACKUP },
                            onOpenStorage = { screen = Screen.STORAGE },
                            onOpenExport = { screen = Screen.EXPORT },
                            onBack = { screen = Screen.MAIN },
                            onOpenSummaries = { screen = Screen.SUMMARIES },
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

                        Screen.SUMMARIES -> SummariesScreen(
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
                                onOpenReminders = { screen = Screen.REMINDERS },
                                onOpenBriefing = { screen = Screen.BRIEFING },
                                onOpenRadio = { screen = Screen.RADIO },
                                onOpenVoiceSettings = { screen = Screen.VOICE_SETTINGS },
                                onTestGps = { BackgroundSensing.testGps(this) },
                                onClearLog = SensingLog::clear,
                            ),
                            modifier = modifier,
                            ruby = RubyUi(
                                chat = chat,
                                voice = voice,
                                hasMicPermission = hasMicPermission,
                                chatActions = chatActions,
                                liveLevel = VoiceSession::liveLevel,
                                radio = radio,
                                radioActions = radioActions,
                                listening = listening.prefs,
                                onListeningOn = { Listening.setOn(applicationContext, it) },
                            ),
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
        if (intent.getBooleanExtra(EXTRA_OPEN_REMINDERS, false)) openRemindersRequested = true
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed a permission or setting, or the car may have
        // connected while Ruby was closed, so check again whenever the screen comes back.
        checkPermissions()
        refreshCarState()
        startSensingIfEnabled()
        // Background listening can only start while Ruby is on screen.
        Listening.appShown(this)
        // Free mode: check Gemini Nano, and make any summaries that are due while Ruby is open.
        lifecycleScope.launch {
            FreeMode.refresh()
            if (AiSettings(applicationContext).current() == AiEngine.FREE) Summaries.catchUpInFreeMode(applicationContext)
        }
    }

    private fun checkPermissions() {
        hasBluetoothPermission = CarBluetooth.hasPermission(this)
        hasActivityPermission = MotionSensor.hasActivityPermission(this)
        hasLocationPermission = GpsReader.hasFineLocation(this)
        hasBackgroundLocation = GpsReader.hasBackgroundLocation(this)
        gpsEnabled = GpsReader(this).isGpsOn()
        batteryOptimized = BackgroundSensing.isBatteryOptimized(this)
        hasMicPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        hasCalendarPermission = CalendarReader.hasPermission(this)
    }

    /** Listens once, and sends what the user said; the reply may then be read aloud. */
    private fun listen() {
        Listening.talk(applicationContext)
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

        /** Intent extra: open straight to the Reminders screen. */
        const val EXTRA_OPEN_REMINDERS = "com.martin.ruby.OPEN_REMINDERS"
    }
}
