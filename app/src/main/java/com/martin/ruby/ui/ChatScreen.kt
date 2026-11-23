package com.martin.ruby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.martin.ruby.chat.ApiKeyStore
import com.martin.ruby.chat.ChatTurn
import com.martin.ruby.chat.ChatUi
import com.martin.ruby.voice.KokoroModel
import com.martin.ruby.voice.KokoroSpeaker
import com.martin.ruby.voice.SpeakMode
import com.martin.ruby.voice.VoiceEngine
import com.martin.ruby.voice.VoicePrefs
import com.martin.ruby.voice.VoiceStyle
import com.martin.ruby.voice.VoiceUi

/** Everything the user can do on the chat screen. */
data class ChatActions(
    val onSend: (String) -> Boolean = { false },
    val onSaveKey: (String) -> Unit = {},
    val onForgetKey: () -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Start listening (the microphone permission is already granted). */
    val onTalk: () -> Unit = {},
    /** Ask Android for the microphone permission, then start listening if granted. */
    val onAllowMic: () -> Unit = {},
    val onDoneTalking: () -> Unit = {},
    val onCancelTalking: () -> Unit = {},
    val onDismissNote: () -> Unit = {},
    val onPlay: (String) -> Unit = {},
    val onStopSpeaking: () -> Unit = {},
    val onSaveVoice: (VoicePrefs) -> Unit = {},
    val onTestVoice: () -> Unit = {},
    val onDownloadKokoro: () -> Unit = {},
    val onCancelKokoro: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Chatting with Claude, as Ruby, by typing or talking. Asks for an API key first if there isn't one. */
@Composable
fun ChatScreen(
    ui: ChatUi,
    voice: VoiceUi,
    hasMicPermission: Boolean,
    context: String?,
    actions: ChatActions,
    modifier: Modifier = Modifier,
) {
    var changingKey by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            TextButton(onClick = actions.onBack) { Text("Back") }
            // Wraps onto a second line on narrow phones.
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                if (voice.speaking) TextButton(onClick = actions.onStopSpeaking) { Text("Stop talking") }
                if (ui.hasKey == true && !changingKey) {
                    TextButton(onClick = actions.onNewConversation, enabled = !ui.sending && ui.turns.isNotEmpty()) {
                        Text("New chat")
                    }
                    TextButton(onClick = { showVoice = true }) { Text("Voice") }
                    TextButton(onClick = { changingKey = true }) { Text("Change key") }
                }
            }
        }
        Text("Chat with Ruby", style = MaterialTheme.typography.headlineSmall)

        when {
            ui.hasKey == null -> Unit // still checking
            ui.hasKey == false || changingKey -> ApiKeyForm(
                replacing = ui.hasKey == true,
                onSave = { key ->
                    actions.onSaveKey(key)
                    changingKey = false
                },
                onForget = {
                    actions.onForgetKey()
                    changingKey = false
                },
                onCancel = { changingKey = false },
            )
            else -> Conversation(ui, voice, hasMicPermission, context, actions, Modifier.weight(1f))
        }
    }

    ui.error?.let { error ->
        AlertDialog(
            onDismissRequest = actions.onDismissError,
            title = { Text("Ruby couldn't answer") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = actions.onDismissError) { Text("OK") } },
        )
    }

    if (showVoice) {
        val appContext = LocalContext.current.applicationContext
        val kokoroFlow = remember { KokoroModel.state(appContext) }
        val kokoro by kokoroFlow.collectAsState(initial = KokoroModel.State.NotInstalled)
        VoiceDialog(
            voice = voice,
            kokoro = kokoro,
            onSave = actions.onSaveVoice,
            onTest = actions.onTestVoice,
            onDownloadKokoro = actions.onDownloadKokoro,
            onCancelKokoro = actions.onCancelKokoro,
            onClose = { showVoice = false },
        )
    }
}

@Composable
private fun Conversation(
    ui: ChatUi,
    voice: VoiceUi,
    hasMicPermission: Boolean,
    context: String?,
    actions: ChatActions,
    modifier: Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var explainMic by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val itemCount = ui.turns.size + if (ui.replying != null) 1 else 0

    // Keep the newest message in view, including while a reply streams in.
    LaunchedEffect(itemCount, ui.replying?.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
    }

    Text(
        "Each message goes to Anthropic's Claude along with what Ruby senses you're doing" +
            (if (context != null) " (now: $context)" else "") + " and a few related past messages " +
            "from memory. Nothing else from the phone's sensors is sent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Box(modifier.fillMaxWidth()) {
        if (itemCount == 0) {
            Text(
                "Ask Ruby anything: type, or tap Talk. Messages and replies are saved to memory.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ui.turns) { _, turn -> Bubble(turn, onPlay = actions.onPlay) }
            ui.replying?.let { soFar ->
                item { Bubble(ChatTurn(fromUser = false, text = soFar.ifEmpty { "…" }), onPlay = null) }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    if (voice.listening) {
        Listening(voice, actions)
        return
    }
    voice.note?.let { note ->
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (voice.note != null) actions.onDismissNote()
            },
            label = { Text("Message") },
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        // One button: Talk when there's nothing typed, Send when there is.
        if (draft.isBlank() && voice.canListen) {
            Button(
                onClick = { if (hasMicPermission) actions.onTalk() else explainMic = true },
                enabled = !ui.sending,
            ) { Text("Talk") }
        } else {
            Button(
                onClick = { if (actions.onSend(draft)) draft = "" },
                enabled = draft.isNotBlank() && !ui.sending,
            ) { Text(if (ui.sending) "…" else "Send") }
        }
    }

    if (explainMic) {
        AlertDialog(
            onDismissRequest = { explainMic = false },
            title = { Text("Talk to Ruby") },
            text = {
                Text(
                    "Ruby needs the microphone to hear you. She only listens after you tap Talk, " +
                        "until you stop speaking."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainMic = false
                    actions.onAllowMic()
                }) { Text("Allow microphone") }
            },
            dismissButton = { TextButton(onClick = { explainMic = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun Listening(voice: VoiceUi, actions: ChatActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            "Listening…",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            voice.heard.ifEmpty { "Go ahead." },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            if (voice.listensOnDevice) "Your voice is turned into text on this phone."
            else "This phone's speech service turns your voice into text; it may send the audio to its maker.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = actions.onDoneTalking) { Text("Done") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = actions.onCancelTalking) { Text("Cancel") }
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn, onPlay: ((String) -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (turn.fromUser) Alignment.End else Alignment.Start) {
        SelectionContainer {
            Text(
                turn.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (turn.fromUser) colors.onPrimaryContainer else colors.onSurface,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        if (turn.fromUser) colors.primaryContainer else colors.surfaceVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        if (turn.memoriesUsed > 0) {
            Text(
                if (turn.memoriesUsed == 1) "Used 1 memory" else "Used ${turn.memoriesUsed} memories",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
        turn.actions.forEach { action ->
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
        if (!turn.fromUser && onPlay != null) {
            TextButton(onClick = { onPlay(turn.text) }) { Text("Play") }
        }
    }
}

/** When Ruby speaks, which voice she uses, and how she sounds. */
@Composable
private fun VoiceDialog(
    voice: VoiceUi,
    kokoro: KokoroModel.State,
    onSave: (VoicePrefs) -> Unit,
    onTest: () -> Unit,
    onDownloadKokoro: () -> Unit,
    onCancelKokoro: () -> Unit,
    onClose: () -> Unit,
) {
    val prefs = voice.prefs
    var speed by remember { mutableFloatStateOf(prefs.style.speed) }
    var pitch by remember { mutableFloatStateOf(prefs.style.pitch) }
    val usingKokoro = voice.usingEngine == VoiceEngine.KOKORO

    fun saveStyle() = onSave(prefs.copy(style = VoiceStyle(speed = speed, pitch = pitch)))

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ruby's voice") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Read replies aloud", style = MaterialTheme.typography.titleSmall)
                SpeakMode.entries.forEach { mode ->
                    Choice(mode.label, mode.explained, selected = prefs.mode == mode) { onSave(prefs.copy(mode = mode)) }
                }

                Spacer(Modifier.height(12.dp))
                Text("Voice", style = MaterialTheme.typography.titleSmall)
                KokoroSpeaker.entries.forEach { speaker ->
                    Choice(
                        "Kokoro: ${speaker.label}" + if (speaker == KokoroSpeaker.HEART) " (Ruby's default)" else "",
                        "Made on this phone; works offline.",
                        selected = prefs.engine == VoiceEngine.KOKORO && prefs.kokoroSpeaker == speaker,
                    ) { onSave(prefs.copy(engine = VoiceEngine.KOKORO, kokoroSpeaker = speaker)) }
                }
                Choice(
                    VoiceEngine.ANDROID.label,
                    "The phone's own voice, set in Android's text-to-speech settings.",
                    selected = prefs.engine == VoiceEngine.ANDROID,
                ) { onSave(prefs.copy(engine = VoiceEngine.ANDROID)) }
                if (prefs.engine == VoiceEngine.KOKORO) KokoroDownload(kokoro, onDownloadKokoro, onCancelKokoro)

                Spacer(Modifier.height(12.dp))
                Text("Speed: %.1f×".format(speed), style = MaterialTheme.typography.titleSmall)
                Slider(value = speed, onValueChange = { speed = it }, onValueChangeFinished = ::saveStyle, valueRange = 0.5f..2.0f)
                // Kokoro has no pitch setting.
                if (!usingKokoro) {
                    Text("Pitch: %.1f×".format(pitch), style = MaterialTheme.typography.titleSmall)
                    Slider(value = pitch, onValueChange = { pitch = it }, onValueChangeFinished = ::saveStyle, valueRange = 0.5f..1.5f)
                }
                voice.problem?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onTest) { Text("Test voice") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/** A radio-button row with a line of explanation. */
@Composable
private fun Choice(label: String, explained: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(explained, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Whether the Kokoro voice is downloaded, and the button to do it. */
@Composable
private fun KokoroDownload(state: KokoroModel.State, onDownload: () -> Unit, onCancel: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    Column(Modifier.padding(start = 32.dp, top = 4.dp)) {
        when (state) {
            KokoroModel.State.Installed -> Text("Downloaded. Ruby speaks with Kokoro.", style = small)
            KokoroModel.State.NotInstalled -> {
                Text(
                    "Needs a one-time download of 126 MB (about 150 MB on the phone), over Wi-Fi. " +
                        "Until then, Ruby uses the phone's own voice.",
                    style = small,
                )
                OutlinedButton(onClick = onDownload) { Text("Download Kokoro voice") }
            }
            is KokoroModel.State.Downloading -> {
                Text(
                    when {
                        state.waitingForWifi -> "Waiting for Wi-Fi to download…"
                        state.fraction == null -> "Starting the download…"
                        state.fraction >= 1f -> "Unpacking…"
                        else -> "Downloading: ${(state.fraction * 100).toInt()}%"
                    },
                    style = small,
                )
                state.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            is KokoroModel.State.Failed -> {
                Text("The download didn't work: ${state.why}", style = small, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onDownload) { Text("Try again") }
            }
        }
    }
}

@Composable
private fun ApiKeyForm(replacing: Boolean, onSave: (String) -> Unit, onForget: () -> Unit, onCancel: () -> Unit) {
    var key by remember { mutableStateOf("") }
    val looksRight = key.trim().startsWith(ApiKeyStore.KEY_PREFIX)

    Spacer(Modifier.height(16.dp))
    Text(
        "Ruby talks to Claude with your own Anthropic API key. Make one at console.anthropic.com " +
            "(API keys), then paste it here. It's saved encrypted on this phone only and isn't " +
            "included in backups.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = key,
        onValueChange = { key = it },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = key.isNotBlank() && !looksRight,
        supportingText = {
            if (key.isNotBlank() && !looksRight) Text("Anthropic keys start with ${ApiKeyStore.KEY_PREFIX}")
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row {
        Button(onClick = { onSave(key) }, enabled = looksRight) { Text("Save key") }
        if (replacing) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onForget) { Text("Remove key", color = MaterialTheme.colorScheme.error) }
        }
    }
}
