package com.martin.ruby.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.ai.FreeMode
import com.martin.ruby.chat.ChatUi

/** Choosing who writes Ruby's words: Claude, or Free mode (Gemini Nano on the phone). */
@Composable
internal fun AiEngineDialog(chat: ChatUi, actions: ChatActions, onClose: () -> Unit) {
    val status by FreeMode.status.collectAsState()
    LaunchedEffect(Unit) { actions.onCheckFreeMode() }
    AiEngineDialogContent(chat, status, actions, onClose)
}

@Composable
internal fun AiEngineDialogContent(chat: ChatUi, status: FreeMode.Status, actions: ChatActions, onClose: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val freePossible = status !is FreeMode.Status.NotOnThisPhone
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ruby's AI") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (engine in AiEngine.entries) {
                    val enabled = engine == AiEngine.CLAUDE || freePossible
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = chat.engine == engine, enabled = enabled, role = Role.RadioButton) {
                                actions.onChooseEngine(engine)
                            }
                            .testTag("engine-${engine.name}")
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        RadioButton(selected = chat.engine == engine, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(engine.label, style = MaterialTheme.typography.bodyLarge)
                            Text(engine.explained, style = small, color = muted)
                            if (engine == AiEngine.CLAUDE && chat.hasKey == false) {
                                Text("Needs your Anthropic API key: add it on the chat screen.", style = small, color = MaterialTheme.colorScheme.error)
                            }
                            if (engine == AiEngine.FREE) FreeModeStatus(status, actions)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Martin's helper line, or on phones without Gemini Nano, that it isn't there.
                Text(
                    if (freePossible) AiEngine.HELPER else AiEngine.NOT_ON_THIS_PHONE,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("engine-helper"),
                )
                if (chat.engine == AiEngine.FREE) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "In Free mode: reminders Free mode can't do are only sent to Claude if you say yes. The morning " +
                            "briefing that plays when your car connects is written by Claude (or, without a key, made on " +
                            "the phone), because Free mode can't run in the background. Summaries are made while Ruby is open.",
                        style = small,
                        color = muted,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
private fun FreeModeStatus(status: FreeMode.Status, actions: ChatActions) {
    val small = MaterialTheme.typography.bodySmall
    when (status) {
        FreeMode.Status.Checking -> Text("Checking this phone…", style = small)
        FreeMode.Status.Ready -> Text("Ready on this phone.", style = small, color = MaterialTheme.colorScheme.primary)
        FreeMode.Status.Downloadable -> {
            Text(
                "Not downloaded yet. Android downloads it (a few GB) over Wi-Fi, and may wait until the phone is " +
                    "charging and idle.",
                style = small,
            )
            OutlinedButton(onClick = actions.onDownloadFree) { Text("Download Free mode") }
        }
        is FreeMode.Status.Downloading -> {
            Text(
                "Downloading" + (status.fraction?.let { ", ${(it * 100).toInt()}%" } ?: "…") +
                    " Android may wait for Wi-Fi, charging or idle.",
                style = small,
            )
            status.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
        FreeMode.Status.NotOnThisPhone -> Text(AiEngine.NOT_ON_THIS_PHONE, style = small)
        is FreeMode.Status.Problem -> Text("Free mode has a problem: ${status.why}", style = small, color = MaterialTheme.colorScheme.error)
    }
}
