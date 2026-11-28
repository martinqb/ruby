package com.martin.ruby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.martin.ruby.listening.Listening
import com.martin.ruby.listening.ListeningPrefs
import com.martin.ruby.listening.VoskModel
import com.martin.ruby.listening.WakePhrase

data class VoiceSettingsActions(
    val onSave: (ListeningPrefs) -> Unit = {},
    val onDownloadModel: () -> Unit = {},
    val onAllowMic: () -> Unit = {},
    /** The words of a wake phrase the listening model can't hear. */
    val unknownWords: suspend (String) -> List<String> = { emptyList() },
    val onBack: () -> Unit = {},
    /** Ruby may open her view from the background ("Display over other apps"), for the wake word with the screen off. */
    val canShowOverScreen: Boolean = true,
    /** She hasn't asked for that yet: ask once, when the wake word is on. */
    val askOverScreen: Boolean = false,
    /** The answer to that one-time ask: true opens Android's settings page for it. */
    val onOverScreenAnswer: (Boolean) -> Unit = {},
)

/** Voice settings: how Ruby listens. The three modes combine; the switch pauses them all. */
@Composable
fun VoiceSettingsScreen(state: Listening.Ui, hasMic: Boolean, actions: VoiceSettingsActions, modifier: Modifier = Modifier) {
    val prefs = state.prefs
    val small = MaterialTheme.typography.bodySmall
    if (prefs.wakeWord && actions.askOverScreen && !actions.canShowOverScreen) OverScreenAsk(actions)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = actions.onBack) { Text("Back") }
        Text("Voice settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text("How Ruby listens. Tick any you like; they work together. The Talk button always works.", style = small, color = muted)
        Spacer(Modifier.height(12.dp))

        // The quick on/off, for battery.
        Row(
            Modifier.fillMaxWidth().toggleable(value = prefs.on, role = Role.Switch) { actions.onSave(prefs.copy(on = it)) }
                .testTag("listening-on"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Listening", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (prefs.on) "On. Turn off to save battery; your choices below stay saved."
                    else "Off to save battery. Your choices below are kept for when you turn it back on.",
                    style = small, color = muted,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = prefs.on, onCheckedChange = null)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        Mode(
            title = "Conversation mode",
            description = "The mic opens again after Ruby answers, so you can keep talking. Say \"that's all\" to stop.",
            checked = prefs.conversation, enabled = prefs.on, tag = "mode-conversation",
        ) { actions.onSave(prefs.copy(conversation = it)) }

        WakeWordMode(prefs, enabled = prefs.on, actions)

        Mode(
            title = "Full duplex",
            description = "Talk over Ruby to interrupt her, like a phone call. Echo cancellation keeps her from hearing herself or the radio.",
            checked = prefs.fullDuplex, enabled = prefs.on, tag = "mode-duplex",
        ) { actions.onSave(prefs.copy(fullDuplex = it)) }

        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        if (prefs.anyMode && !hasMic) {
            Text("Ruby needs the microphone to listen.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = actions.onAllowMic) { Text("Allow the microphone") }
            Spacer(Modifier.height(12.dp))
        }
        if (prefs.needsModel) ModelStatus(state.model, actions)
        state.problem?.takeIf { prefs.anyMode && hasMic }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = small, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "While any of these listen with Ruby in the background, her notification says so and has a Turn off button. " +
                "The wake word and full duplex listen on the phone; nothing is sent until she's actually listening to you. " +
                "Background listening starts when you open Ruby (Android doesn't let it start on its own after a restart).",
            style = small, color = muted,
        )
    }
}

@Composable
private fun Mode(title: String, description: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The wake word's tick box, with its text box right beside it. */
@Composable
private fun WakeWordMode(prefs: ListeningPrefs, enabled: Boolean, actions: VoiceSettingsActions) {
    var text by rememberSaveable(prefs.wakePhrase) { mutableStateOf(prefs.wakePhrase) }
    var unknown by remember { mutableStateOf<List<String>>(emptyList()) }
    val problem = WakePhrase.problem(text)
    val changed = WakePhrase.normalize(text) != WakePhrase.normalize(prefs.wakePhrase)
    LaunchedEffect(prefs.wakePhrase) { unknown = actions.unknownWords(prefs.wakePhrase) }
    fun save() {
        if (problem == null && changed) actions.onSave(prefs.copy(wakePhrase = text.trim()))
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = prefs.wakeWord, enabled = enabled,
                onCheckedChange = { actions.onSave(prefs.copy(wakeWord = it)) },
                modifier = Modifier.testTag("mode-wake"),
            )
            Spacer(Modifier.width(8.dp))
            Text("Wake word", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, enabled = enabled,
                isError = problem != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f).testTag("wake-phrase"),
            )
            if (changed && problem == null) {
                TextButton(onClick = ::save, modifier = Modifier.testTag("wake-save")) { Text("Save") }
            }
        }
        Column(Modifier.padding(start = 56.dp)) {
            Text(
                "Ruby listens for your wake word, then starts listening. Uses more battery.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (prefs.wakeWord && !actions.canShowOverScreen) {
                Text(
                    "With the screen off, Ruby shows herself through a notification. To have her turn the screen on " +
                        "and show her orb, allow \"Display over other apps\".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { actions.onOverScreenAnswer(true) }, modifier = Modifier.testTag("allow-over-screen")) { Text("Allow") }
            }
            if (problem == null && !changed && unknown.isNotEmpty()) {
                Text(
                    "Ruby's listening model doesn't know " + unknown.joinToString(" or ") { "\"$it\"" } +
                        ", so she won't hear it. Try a common word.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The one-time ask for "Display over other apps", with why. */
@Composable
private fun OverScreenAsk(actions: VoiceSettingsActions) {
    AlertDialog(
        onDismissRequest = { actions.onOverScreenAnswer(false) },
        title = { Text("Show Ruby when you call her") },
        text = {
            Text(
                "When you say the wake word with the screen off, Ruby can turn the screen on and show her orb and your " +
                    "conversation over the lock screen. Android calls this \"Display over other apps\". She never unlocks " +
                    "your phone: to open her full screen, you unlock it yourself. Without it, she shows a notification instead.",
            )
        },
        confirmButton = { TextButton(onClick = { actions.onOverScreenAnswer(true) }, modifier = Modifier.testTag("over-screen-open")) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { actions.onOverScreenAnswer(false) }, modifier = Modifier.testTag("over-screen-not-now")) { Text("Not now") } },
    )
}

@Composable
private fun ModelStatus(model: VoskModel.State, actions: VoiceSettingsActions) {
    val small = MaterialTheme.typography.bodySmall
    when (model) {
        VoskModel.State.Installed -> Text("The on-phone listening model is installed.", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is VoskModel.State.Downloading -> Text(
            if (model.waitingForWifi) "The listening model will download on Wi-Fi."
            else "Downloading the listening model… " + (model.fraction?.let { "${(it * 100).toInt()}%" } ?: ""),
            style = small,
        )
        VoskModel.State.NotInstalled, is VoskModel.State.Failed -> {
            Text(
                "The wake word and full duplex need Ruby's on-phone listening model (${VoskModel.DOWNLOAD_MB} MB, once, over Wi-Fi)." +
                    if (model is VoskModel.State.Failed) " Last try: ${model.why}" else "",
                style = small,
            )
            OutlinedButton(onClick = actions.onDownloadModel, modifier = Modifier.testTag("model-download")) { Text("Download it") }
        }
    }
}
