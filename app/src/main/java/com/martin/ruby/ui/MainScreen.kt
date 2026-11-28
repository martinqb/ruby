package com.martin.ruby.ui

import androidx.compose.material3.FilterChip
import com.martin.ruby.listening.ListeningPrefs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.martin.ruby.BuildConfig
import com.martin.ruby.chat.ChatUi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.FlowRow
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.radio.Radio
import com.martin.ruby.ui.orb.AuroraOrb
import com.martin.ruby.ui.orb.AuroraPalette
import com.martin.ruby.ui.orb.OrbState
import com.martin.ruby.ui.orb.orbStateFor
import com.martin.ruby.voice.VoiceUi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.martin.ruby.data.Car
import com.martin.ruby.decision.ContextReading
import com.martin.ruby.decision.ContextState
import com.martin.ruby.location.LocationReading
import com.martin.ruby.log.LogEntry
import com.martin.ruby.motion.Motion
import com.martin.ruby.motion.MotionReading
import com.martin.ruby.motion.Rhythm
import com.martin.ruby.ui.theme.RubyTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything the main screen shows. */
data class MainUiState(
    val car: Car? = null,
    val driving: Boolean = false,
    /** The decision engine's state, or null while background sensing isn't running. */
    val context: ContextReading? = null,
    val motion: MotionReading? = null,
    val location: LocationReading = LocationReading(),
    val savedPlaceCount: Int = 0,
    val hasBluetoothPermission: Boolean = false,
    val hasActivityPermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val hasBackgroundLocation: Boolean = false,
    val gpsEnabled: Boolean = true,
    val backgroundSensing: Boolean = true,
    val batteryOptimized: Boolean = false,
    val log: List<LogEntry> = emptyList(),
)

/** Everything the user can do from the main screen. */
data class MainActions(
    val onRequestPermissions: () -> Unit = {},
    val onChooseCar: () -> Unit = {},
    val onBackgroundSensingChange: (Boolean) -> Unit = {},
    val onAllowBackground: () -> Unit = {},
    val onAllowLocation: () -> Unit = {},
    val onAllowLocationAllTheTime: () -> Unit = {},
    val onOpenPlaces: () -> Unit = {},
    val onOpenChat: () -> Unit = {},
    val onOpenMemory: () -> Unit = {},
    val onOpenReminders: () -> Unit = {},
    val onOpenBriefing: () -> Unit = {},
    val onOpenRadio: () -> Unit = {},
    val onOpenVoiceSettings: () -> Unit = {},
    val onTestGps: () -> Unit = {},
    val onClearLog: () -> Unit = {},
)

/** Ruby herself on the main screen: her orb, her latest reply and the chat box. */
data class RubyUi(
    val chat: ChatUi = ChatUi(),
    val voice: VoiceUi = VoiceUi(),
    val hasMicPermission: Boolean = false,
    val chatActions: ChatActions = ChatActions(),
    /** The live audio level for the orb (see VoiceSession.liveLevel). */
    val liveLevel: () -> Float = { -1f },
    val radio: Radio.State = Radio.State(),
    val radioActions: RadioActions = RadioActions(),
    /** The listening modes, for the quick on/off. */
    val listening: ListeningPrefs = ListeningPrefs(),
    val onListeningOn: (Boolean) -> Unit = {},
)

/** The home screen: Ruby's orb and chat box first, then what she senses, then setup. */
@Composable
fun MainScreen(state: MainUiState, actions: MainActions, modifier: Modifier = Modifier, ruby: RubyUi = RubyUi()) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RubySection(
            ruby, onOpenChat = actions.onOpenChat, onOpenBriefing = actions.onOpenBriefing, onOpenRadio = actions.onOpenRadio,
            onOpenVoiceSettings = actions.onOpenVoiceSettings,
        )
        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(24.dp))
        ContextHeadline(state)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (state.car == null) "No car chosen yet" else "Car: ${state.car.name}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        MotionInfo(state.motion)
        if (state.hasLocationPermission) {
            Spacer(Modifier.height(16.dp))
            LocationInfo(state)
        }
        Spacer(Modifier.height(32.dp))

        if (!state.hasBluetoothPermission) {
            Explained(
                "Ruby needs Bluetooth access to notice when your phone connects to your car.",
                "Allow Bluetooth access",
                actions.onRequestPermissions,
            )
            return@Column
        }

        OutlinedButton(onClick = actions.onChooseCar) {
            Text(if (state.car == null) "Choose your car" else "Change car")
        }
        if (state.hasLocationPermission) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = actions.onOpenPlaces) {
                Text(if (state.savedPlaceCount == 0) "Save places (home, work...)" else "Saved places (${state.savedPlaceCount})")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = actions.onOpenMemory) { Text("Memory") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = actions.onOpenReminders) { Text("Reminders") }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sense in the background", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keeps working when Ruby is closed or the phone restarts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = state.backgroundSensing, onCheckedChange = actions.onBackgroundSensingChange)
        }

        if (!state.backgroundSensing) return@Column

        if (!state.hasActivityPermission) {
            Explained(
                "Allow Physical activity so Ruby can count steps and tell when you're " +
                    "walking. It stays on your phone.",
                "Allow Physical activity",
                actions.onRequestPermissions,
            )
        }
        when {
            !state.hasLocationPermission -> Explained(
                "Allow location so GPS can confirm you're in a vehicle and recognize places " +
                    "you save. GPS only turns on briefly, and your location stays on your phone.",
                "Allow location",
                actions.onAllowLocation,
            )
            !state.gpsEnabled -> Explained(
                "Location is turned off in Android's settings, so GPS can't be used.",
                null,
                {},
            )
            !state.hasBackgroundLocation -> Explained(
                "Optional: choose \"Allow all the time\" so GPS also works after the phone " +
                    "restarts, before you've opened Ruby.",
                "Allow all the time",
                actions.onAllowLocationAllTheTime,
            )
        }
        if (state.batteryOptimized) {
            Explained(
                "Android may stop Ruby to save battery. Let Ruby run in the background " +
                    "so it doesn't miss the car connecting.",
                "Allow background use",
                actions.onAllowBackground,
            )
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        SensingLogSection(state, actions)
    }
}

/**
 * The orb (if it's on), what it's showing in words, Ruby's latest reply, and the chat box.
 * In debug builds a long press on the orb shows each of its states in turn.
 */
@Composable
private fun RubySection(
    ruby: RubyUi,
    onOpenChat: () -> Unit,
    onOpenBriefing: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val chat = ruby.chat
    val voice = ruby.voice
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showAi by rememberSaveable { mutableStateOf(false) }
    var demo by remember { mutableStateOf<OrbState?>(null) }
    val shown = demo ?: orbStateFor(chat, voice)

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (voice.speaking) TextButton(onClick = ruby.chatActions.onStopSpeaking) { Text("Stop talking") }
        if (ruby.radio.on) TextButton(onClick = ruby.radioActions.onStop) { Text("Stop radio") }
        TextButton(onClick = onOpenRadio) { Text("Radio") }
        TextButton(onClick = { showAi = true }) { Text(if (chat.engine == AiEngine.FREE) "AI: Free mode" else "AI: Claude") }
        TextButton(onClick = onOpenBriefing) { Text("Briefing") }
        TextButton(onClick = { showVoice = true }) { Text("Voice and orb") }
        TextButton(onClick = onOpenVoiceSettings, modifier = Modifier.testTag("open-voice-settings")) { Text("Voice settings") }
        // The quick on/off for the listening modes (battery), once any is ticked.
        if (ruby.listening.anyMode) {
            FilterChip(
                selected = ruby.listening.on,
                onClick = { ruby.onListeningOn(!ruby.listening.on) },
                label = { Text(if (ruby.listening.on) "Listening: on" else "Listening: off") },
                modifier = Modifier.testTag("listening-chip"),
            )
        }
    }
    if (voice.prefs.showOrb) {
        AuroraOrb(
            state = shown,
            level = if (demo != null) DEMO_LEVEL else ruby.liveLevel,
            modifier = Modifier.testTag(ORB_TAG),
            theme = voice.prefs.orbTheme,
            onLongPress = if (!BuildConfig.DEBUG) null else {
                { demo = OrbState.entries.getOrNull((demo?.ordinal ?: -1) + 1) }
            },
        )
        Text(
            shown.label + if (demo != null) " (demo: long-press for the next)" else "",
            style = MaterialTheme.typography.titleMedium,
            color = if (shown == OrbState.ERROR) errorAmber() else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (shown == OrbState.ERROR) {
            // In the demo, an example of the reason shown under a real error.
            (if (demo != null) "Example: Ruby couldn't reach Claude. Check the internet." else chat.error ?: voice.problem)
                ?.let { Details(it) }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Her latest reply (the end of it while it's still arriving).
    val replying = chat.replying
    val caption = when {
        replying != null -> replying.ifEmpty { "…" }.let { if (it.length > CAPTION_TAIL) "…" + it.takeLast(CAPTION_TAIL) else it }
        else -> chat.turns.lastOrNull { !it.fromUser }?.text
    }
    Text(
        caption ?: "Ask Ruby anything: type, or tap Talk.",
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        color = if (caption == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
    when {
        chat.hasKey == null && chat.engine == AiEngine.CLAUDE -> Unit // still checking
        !chat.canChat -> Button(onClick = onOpenChat) { Text("Set up chat with Claude") }
        else -> ChatInput(chat, voice, ruby.hasMicPermission, ruby.chatActions)
    }
    TextButton(onClick = onOpenChat) { Text("Full conversation") }

    ChatErrorDialog(chat, ruby.chatActions.onDismissError)
    if (showVoice) VoiceSettingsDialog(voice, ruby.chatActions, onClose = { showVoice = false })
    if (showAi) AiEngineDialog(chat, ruby.chatActions, onClose = { showAi = false })
}

/** The orb's amber error color for text: bright on dark backgrounds, darker on light ones to stay readable. */
@Composable
private fun errorAmber() = Color(
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) AuroraPalette.AMBER else AuroraPalette.AMBER_TEXT_ON_LIGHT
)

/** The orb's test tag, for UI tests. */
const val ORB_TAG = "ruby-orb"

/** How much of a reply still arriving is shown under the orb. */
private const val CAPTION_TAIL = 280

/** In the demo, each state shows its own made-up motion. */
private val DEMO_LEVEL: () -> Float = { -1f }

/**
 * The one current state from the decision engine, with why and since when, plus any new
 * state waiting to hold long enough. Without background sensing, only the car can be sensed.
 */
@Composable
private fun ContextHeadline(state: MainUiState) {
    val context = state.context
    val driving = context?.state?.kind?.let { it == ContextState.Kind.DRIVING } ?: state.driving
    Text(
        text = context?.state?.label ?: if (state.driving) "Driving" else "Not driving",
        style = MaterialTheme.typography.displayMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = if (driving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
    if (context == null) return
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val since = if (context.state.kind == ContextState.Kind.UNKNOWN) "" else
        ", since ${timeFormat.format(Date(context.sinceMillis))}"
    Details(context.reason.replaceFirstChar { it.uppercase() } + since)
    context.pending?.let { next ->
        Details("Changing to ${next.label} if it holds for ${context.pendingHoldMillis / 1000} s")
    }
}

/** The on-screen log: what sensing and GPS have been doing, newest first. */
@Composable
private fun SensingLogSection(state: MainUiState, actions: MainActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (state.hasLocationPermission) {
            TextButton(onClick = actions.onTestGps) { Text("Test GPS now") }
        }
        TextButton(onClick = actions.onClearLog, enabled = state.log.isNotEmpty()) { Text("Clear") }
    }
    if (state.log.isEmpty()) {
        Details("Nothing yet. GPS checks, speeds and errors will show up here.")
        return
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth()) {
        state.log.forEach { entry ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = timeFormat.format(Date(entry.timeMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(entry.message, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A short explanation with an optional button underneath. */
@Composable
private fun Explained(text: String, buttonLabel: String?, onClick: () -> Unit) {
    Spacer(Modifier.height(24.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    if (buttonLabel != null) {
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClick) { Text(buttonLabel) }
    }
}

/** The motion result, plus the numbers behind it so the thresholds can be tuned. */
@Composable
private fun MotionInfo(reading: MotionReading?) {
    Text(
        text = "Motion: ${reading?.motion?.label ?: "not sensing"}",
        style = MaterialTheme.typography.titleMedium,
    )
    val details = when {
        reading == null -> "Turn on background sensing to start."
        reading.paused -> "Sensors paused until the phone moves (saves battery)."
        else -> buildString {
            append("Variation %.2f m/s²".format(reading.variation))
            append(" · ")
            append(reading.stepsPerSecond?.let { "%.1f steps/s".format(it) } ?: "no step counter")
            append("\nRhythm %.2f".format(reading.rhythm.strength))
            reading.rhythm.perSecond?.let { append(" at %.1f beats/s".format(it)) }
        }
    }
    Details(details)
}

/** What GPS has found: vehicle speed and saved place. */
@Composable
private fun LocationInfo(state: MainUiState) {
    val location = state.location
    val speed = location.speedMph?.let { "%.0f mph".format(it) }
    Text(
        text = when {
            location.inVehicle && location.gpsOn -> "GPS: In a vehicle" + (speed?.let { ", $it" } ?: "")
            location.inVehicle -> "GPS: In a vehicle"
            location.gpsOn -> "GPS: on" + (speed?.let { ", $it" } ?: ", finding satellites…")
            else -> "GPS: off"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    Details(
        when {
            location.inVehicle || location.gpsOn ->
                "Over 15 mph for 10 seconds confirms a vehicle. Ends after 2 minutes under 3 mph or " +
                    "without a GPS fix, or when the car disconnects and you're still or walking."
            else -> "Turns on when motion looks like a vehicle or the car's Bluetooth connects."
        }
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = "Place: ${location.place ?: "none"}",
        style = MaterialTheme.typography.titleMedium,
    )
    Details(
        if (state.savedPlaceCount == 0) "Save places to have them recognized."
        else "Within 100 m of a saved place. Checked when you stop moving."
    )
}

@Composable
private fun Details(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun MainScreenPreview() {
    RubyTheme {
        MainScreen(
            state = MainUiState(
                car = Car("00:11:22:33:44:55", "Corolla"),
                driving = true,
                context = ContextReading(
                    state = ContextState(ContextState.Kind.DRIVING),
                    reason = "car Bluetooth connected",
                    sinceMillis = 1_700_000_000_000,
                ),
                motion = MotionReading(
                    Motion.IN_VEHICLE,
                    variation = 0.42f,
                    stepsPerSecond = 0f,
                    rhythm = Rhythm(strength = 0.08f, perSecond = null),
                ),
                location = LocationReading(gpsOn = false, speedMph = 34f, inVehicle = true),
                savedPlaceCount = 2,
                hasBluetoothPermission = true,
                hasActivityPermission = true,
                hasLocationPermission = true,
                hasBackgroundLocation = false,
                batteryOptimized = true,
                log = listOf(
                    LogEntry(1_700_000_030_000, "Over 15 mph for 15 s: in a vehicle. GPS off."),
                    LogEntry(1_700_000_020_000, "Speed 31 mph ±5 m."),
                    LogEntry(1_700_000_010_000, "First GPS fix."),
                    LogEntry(1_700_000_000_000, "GPS on (motion suggests a vehicle). Checking speed."),
                ),
            ),
            actions = MainActions(),
        )
    }
}
