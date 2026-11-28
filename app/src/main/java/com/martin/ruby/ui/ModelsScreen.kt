package com.martin.ruby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.ai.models.LocalModel
import com.martin.ruby.ai.models.LocalModels
import com.martin.ruby.ai.models.LocalServer
import com.martin.ruby.ai.models.ModelCatalog
import com.martin.ruby.ai.models.ModelChoices
import com.martin.ruby.ai.models.ModelSafety
import com.martin.ruby.ai.models.RamCheck
import com.martin.ruby.ai.models.RamFit
import com.martin.ruby.ai.models.RemoteEndpoint
import kotlinx.coroutines.launch

/** What the Models screen shows. */
data class ModelsUi(
    val engine: AiEngine = AiEngine.CLAUDE,
    val choices: ModelChoices = ModelChoices(),
    val states: Map<String, LocalModels.State> = emptyMap(),
    val server: LocalServer.Status = LocalServer.Status.Off,
    val freeBytes: Long = 0,
    val totalRam: Long = 0,
    /** How each model fits in memory, by id. */
    val fits: Map<String, RamFit?> = emptyMap(),
    /** This phone can run on-phone models (arm64 with llama.cpp in the build). */
    val localPossible: Boolean = true,
    /** Which Cloud/PC entries have a key saved. */
    val keys: Set<String> = emptySet(),
)

data class ModelsActions(
    val onUseEngine: (AiEngine) -> Unit = {},
    val onChooseLocal: (LocalModel) -> Unit = {},
    val onDownload: (LocalModel) -> Unit = {},
    val onDelete: (LocalModel) -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    /** Adds a GGUF link; returns a problem to show, or null. */
    val onAddLink: (String, String) -> String? = { _, _ -> null },
    val onRemoveLocal: (LocalModel) -> Unit = {},
    val onChooseRemote: (RemoteEndpoint) -> Unit = {},
    val onSaveRemote: (RemoteEndpoint, String?) -> Unit = { _, _ -> },
    val onRemoveRemote: (RemoteEndpoint) -> Unit = {},
    /** Tests a Cloud/PC entry (with the key typed, if any); returns what to show. */
    val onTest: suspend (RemoteEndpoint, String?) -> String = { _, _ -> "" },
    val onBack: () -> Unit = {},
)

@Composable
fun ModelsScreen(ui: ModelsUi, actions: ModelsActions, modifier: Modifier = Modifier) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = actions.onBack) { Text("Back") }
        Text("Models", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Now answering: " + (com.martin.ruby.ai.models.ModelEngine.label(ui.engine, ui.choices) ?: ui.engine.label),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("models-active"),
        )
        Text(
            "Ruby's memory, instructions and project knowledge are the same with every model. With models other " +
                "than Claude, " + ModelSafety.CLAUDE_ONLY.joinToString(", ").lowercase().replaceFirstChar { it.uppercase() } +
                " aren't available, and the phone enforces her safety rules itself.",
            style = small, color = muted,
        )
        FlowRow {
            for (e in listOf(AiEngine.CLAUDE, AiEngine.FREE, AiEngine.LOCAL, AiEngine.REMOTE)) {
                TextButton(onClick = { actions.onUseEngine(e) }, modifier = Modifier.testTag("use-${e.name}")) {
                    Text((if (ui.engine == e) "✓ " else "") + e.label)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ---- On-phone models
        Text("On-phone models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Offline, no API key. One loads at a time, and it's unloaded after 15 minutes unused. " +
                "Free space: ${RamCheck.gb(ui.freeBytes)} · Memory: ${RamCheck.gb(ui.totalRam)}.",
            style = small, color = muted,
        )
        if (!ui.localPossible) Text("This device can't run on-phone models (they need an arm64 phone).", style = small, color = MaterialTheme.colorScheme.error)
        when (val s = ui.server) {
            is LocalServer.Status.Loading -> Text("Loading ${s.model}…", style = small)
            is LocalServer.Status.Running -> Text("${s.model} is loaded.", style = small, color = MaterialTheme.colorScheme.primary)
            is LocalServer.Status.Failed -> Text("The on-phone model failed: ${s.why}", style = small, color = MaterialTheme.colorScheme.error)
            LocalServer.Status.Off -> Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ui.choices.wifiOnly, onCheckedChange = actions.onWifiOnly, modifier = Modifier.testTag("wifi-only"))
            Spacer(Modifier.width(8.dp))
            Text("Download only on Wi-Fi", style = MaterialTheme.typography.bodyMedium)
        }
        for (model in ui.choices.local) {
            LocalModelCard(model, ui, actions)
        }
        AddLink(actions)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // ---- Cloud or PC models
        Text("Cloud or PC models", style = MaterialTheme.typography.titleMedium)
        Text(
            "Any OpenAI-compatible service. Keys are stored encrypted on the phone. Your messages and their memory " +
                "notes go to that service to be answered.",
            style = small, color = muted,
        )
        for (endpoint in ui.choices.remote) {
            RemoteCard(endpoint, ui, actions)
        }
        var adding by rememberSaveable { mutableStateOf(false) }
        if (adding) {
            RemoteEditor(
                RemoteEndpoint("custom-" + System.currentTimeMillis(), "", "https://", "", needsKey = true),
                hasKey = false,
                onSave = { e, key -> actions.onSaveRemote(e, key); adding = false },
                onCancel = { adding = false },
                onTest = actions.onTest,
            )
        } else {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.testTag("add-remote")) { Text("Add a service") }
        }
    }
}

@Composable
private fun LocalModelCard(model: LocalModel, ui: ModelsUi, actions: ModelsActions) {
    val small = MaterialTheme.typography.bodySmall
    val state = ui.states[model.id] ?: LocalModels.State.NotDownloaded
    val chosen = ui.choices.chosenLocal == model.id
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("local-${model.id}")) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.selectable(selected = chosen, enabled = state == LocalModels.State.Ready, role = Role.RadioButton) {
                    actions.onChooseLocal(model)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = chosen, onClick = null, enabled = state == LocalModels.State.Ready)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(model.name, style = MaterialTheme.typography.bodyLarge)
                    Text((model.bytes?.let { RamCheck.gb(it) } ?: "Size unknown until downloaded") + " · " + model.fileName, style = small)
                }
            }
            model.note?.let { Text(it, style = small, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val fit = ui.fits[model.id]
            if (fit != null && model.bytes != null) RamCheck.warning(fit, model.bytes, ui.totalRam)?.let {
                Text(it, style = small, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ram-${model.id}"))
            }
            val toolsKey = ModelChoices.localKey(model)
            ui.choices.tools[toolsKey]?.let { works ->
                Text(if (works) "Can use tools: reminders work." else "Can't use tools: actions are off with this model.", style = small)
            }
            when (state) {
                LocalModels.State.NotDownloaded -> Button(onClick = { actions.onDownload(model) }, enabled = ui.localPossible, modifier = Modifier.testTag("download-${model.id}")) {
                    Text("Download")
                }
                is LocalModels.State.Downloading -> {
                    val fraction = state.total?.let { (state.done.toFloat() / it).coerceIn(0f, 1f) }
                    Text(
                        if (state.waitingForWifi) "Waiting for Wi-Fi…" else "Downloading… " +
                            (fraction?.let { "${(it * 100).toInt()}% (${RamCheck.gb(state.done)})" } ?: RamCheck.gb(state.done)),
                        style = small,
                    )
                    fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    TextButton(onClick = { actions.onDelete(model) }) { Text("Cancel") }
                }
                LocalModels.State.Checking -> Text("Checking the download (this takes a minute)…", style = small)
                LocalModels.State.Ready -> Row {
                    if (!chosen) TextButton(onClick = { actions.onChooseLocal(model) }) { Text("Use this model") }
                    TextButton(onClick = { actions.onDelete(model) }, modifier = Modifier.testTag("delete-${model.id}")) { Text("Delete") }
                }
                is LocalModels.State.Failed -> {
                    Text(state.why, style = small, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { actions.onDownload(model) }) { Text("Try again") }
                }
            }
            if (model.custom) TextButton(onClick = { actions.onRemoveLocal(model) }) { Text("Remove from list") }
        }
    }
}

@Composable
private fun AddLink(actions: ModelsActions) {
    var link by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("Add another GGUF model", style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = link, onValueChange = { link = it; problem = null }, singleLine = true,
            label = { Text("Link to a .gguf file (https://…)") },
            modifier = Modifier.fillMaxWidth().testTag("gguf-link"),
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = {
            problem = actions.onAddLink(link, name)
            if (problem == null) {
                link = ""
                name = ""
            }
        }, modifier = Modifier.testTag("add-link")) { Text("Add") }
    }
}

@Composable
private fun RemoteCard(endpoint: RemoteEndpoint, ui: ModelsUi, actions: ModelsActions) {
    var editing by rememberSaveable(endpoint.id) { mutableStateOf(false) }
    val chosen = ui.choices.chosenRemote == endpoint.id
    val small = MaterialTheme.typography.bodySmall
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("remote-${endpoint.id}")) {
        Column(Modifier.padding(12.dp)) {
            if (editing) {
                RemoteEditor(
                    endpoint, hasKey = endpoint.id in ui.keys,
                    onSave = { e, key -> actions.onSaveRemote(e, key); editing = false },
                    onCancel = { editing = false },
                    onTest = actions.onTest,
                )
            } else {
                Row(
                    Modifier.selectable(selected = chosen, role = Role.RadioButton) { actions.onChooseRemote(endpoint) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = chosen, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(endpoint.name, style = MaterialTheme.typography.bodyLarge)
                        Text(endpoint.baseUrl + " · " + endpoint.model.ifBlank { "no model name yet" }, style = small)
                        if (endpoint.needsKey) Text(if (endpoint.id in ui.keys) "Key saved" else "Needs an API key", style = small)
                        ModelCatalog.baseUrlProblem(endpoint.baseUrl)?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
                    }
                }
                ui.choices.tools[ModelChoices.remoteKey(endpoint)]?.let { works ->
                    Text(if (works) "Can use tools: reminders work." else "Can't use tools: actions are off with this model.", style = small)
                }
                Row {
                    TextButton(onClick = { editing = true }, modifier = Modifier.testTag("edit-${endpoint.id}")) { Text("Edit") }
                    TextButton(onClick = { actions.onRemoveRemote(endpoint) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun RemoteEditor(
    endpoint: RemoteEndpoint,
    hasKey: Boolean,
    onSave: (RemoteEndpoint, String?) -> Unit,
    onCancel: () -> Unit,
    onTest: suspend (RemoteEndpoint, String?) -> String,
) {
    var name by rememberSaveable { mutableStateOf(endpoint.name) }
    var url by rememberSaveable { mutableStateOf(endpoint.baseUrl) }
    var model by rememberSaveable { mutableStateOf(endpoint.model) }
    var key by remember { mutableStateOf("") }
    var needsKey by rememberSaveable { mutableStateOf(endpoint.needsKey) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun current() = endpoint.copy(name = name.trim().ifEmpty { "My service" }, baseUrl = url.trim(), model = model.trim(), needsKey = needsKey)
    Column {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            url, { url = it }, label = { Text("Base URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("remote-url"),
        )
        OutlinedTextField(model, { model = it }, label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("remote-model"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = needsKey, onCheckedChange = { needsKey = it })
            Spacer(Modifier.width(8.dp))
            Text("Needs an API key")
        }
        if (needsKey) {
            OutlinedTextField(
                key, { key = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                label = { Text(if (hasKey) "API key (saved; type to replace)" else "API key") },
                modifier = Modifier.fillMaxWidth().testTag("remote-key"),
            )
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("test-result")) }
        FlowRow {
            TextButton(onClick = { onSave(current(), key.takeIf { it.isNotBlank() }) }, modifier = Modifier.testTag("remote-save")) { Text("Save") }
            TextButton(enabled = !testing, onClick = {
                testing = true
                result = "Testing…"
                scope.launch {
                    result = onTest(current(), key.takeIf { it.isNotBlank() })
                    testing = false
                }
            }, modifier = Modifier.testTag("remote-test")) { Text("Test connection") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
