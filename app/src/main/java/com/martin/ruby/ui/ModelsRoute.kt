package com.martin.ruby.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.ai.AiSettings
import com.martin.ruby.ai.models.LocalModels
import com.martin.ruby.ai.models.LocalServer
import com.martin.ruby.ai.models.ModelCatalog
import com.martin.ruby.ai.models.ModelChoices
import com.martin.ruby.ai.models.ModelStore
import com.martin.ruby.ai.models.OpenAiChat
import com.martin.ruby.chat.ClaudeChat
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Models screen with its state and what its buttons do. */
@Composable
fun ModelsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val store = remember { ModelStore(context) }
    val scope = rememberCoroutineScope()
    val engine by remember { AiSettings(context).engine }.collectAsState(initial = AiEngine.CLAUDE)
    val choices by store.choices.collectAsState(initial = ModelChoices())
    val states by LocalModels.states.collectAsState()
    val server by LocalServer.status.collectAsState()
    var keys by remember { mutableStateOf(emptySet<String>()) }
    var freeBytes by remember { mutableStateOf(0L) }

    // While the screen is open: download progress, free space and memory, once a second.
    LaunchedEffect(choices.local) {
        while (true) {
            LocalModels.refresh(context, choices.local)
            freeBytes = LocalModels.freeBytes(context)
            keys = choices.remote.filter { store.hasKey(it.id) }.map { it.id }.toSet()
            delay(1_000)
        }
    }

    val ui = ModelsUi(
        engine = engine,
        choices = choices,
        states = states,
        server = server,
        freeBytes = freeBytes,
        totalRam = LocalModels.totalRam(context),
        fits = choices.local.associate { it.id to LocalModels.fit(context, it) },
        localPossible = LocalServer.available(context),
        keys = keys,
    )
    val actions = ModelsActions(
        onUseEngine = { e ->
            scope.launch {
                AiSettings(context).save(e)
                SensingLog.add("AI engine: ${e.label}.")
                if (e != AiEngine.LOCAL) LocalServer.stop()
            }
        },
        onChooseLocal = { m ->
            scope.launch {
                store.chooseLocal(m.id)
                AiSettings(context).save(AiEngine.LOCAL)
                SensingLog.add("AI engine: on-phone model ${m.name}.")
            }
        },
        onDownload = { m ->
            LocalModels.download(context, m, choices.wifiOnly)?.let { problem -> SensingLog.add("Models: $problem") }
        },
        onDelete = { m ->
            scope.launch {
                if (choices.chosenLocal == m.id) {
                    LocalServer.stop()
                    store.chooseLocal(null)
                }
                LocalModels.delete(context, m)
            }
        },
        onWifiOnly = { on -> scope.launch { store.setWifiOnly(on) } },
        onAddLink = { link, name ->
            ModelCatalog.fromLink(link, name).fold(
                onSuccess = { m ->
                    if (choices.local.any { it.fileName == m.fileName }) "That model is already in the list."
                    else {
                        scope.launch { store.addLocal(m) }
                        null
                    }
                },
                onFailure = { it.message },
            )
        },
        onRemoveLocal = { m ->
            scope.launch {
                LocalModels.delete(context, m)
                store.removeLocal(m.id)
            }
        },
        onChooseRemote = { e ->
            scope.launch {
                store.chooseRemote(e.id)
                AiSettings(context).save(AiEngine.REMOTE)
                LocalServer.stop()
                SensingLog.add("AI engine: ${e.name}.")
            }
        },
        onSaveRemote = { e, key ->
            scope.launch {
                store.saveRemote(e)
                if (key != null) store.saveKey(e.id, key)
            }
        },
        onRemoveRemote = { e -> scope.launch { store.removeRemote(e.id) } },
        onTest = { e, typedKey ->
            ModelCatalog.baseUrlProblem(e.baseUrl) ?: try {
                val key = typedKey ?: store.loadKey(e.id)
                val chat = OpenAiChat(e.baseUrl, key, e.model, e.name)
                val connected = chat.testConnection()
                if (e.model.isBlank()) connected else {
                    val tools = chat.canUseTools()
                    store.setTools(ModelChoices.remoteKey(e), tools)
                    connected + if (tools) " It can use tools." else " It can't use tools, so actions will be off with it."
                }
            } catch (err: ClaudeChat.ChatError) {
                err.message ?: "It didn't work."
            }
        },
        onBack = onBack,
    )
    ModelsScreen(ui, actions, modifier)
}
