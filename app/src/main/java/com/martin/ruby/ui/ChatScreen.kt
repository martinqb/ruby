package com.martin.ruby.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.martin.ruby.chat.ApiKeyStore
import com.martin.ruby.chat.ChatTurn
import com.martin.ruby.chat.ChatUi

/** Everything the user can do on the chat screen. */
data class ChatActions(
    val onSend: (String) -> Boolean = { false },
    val onSaveKey: (String) -> Unit = {},
    val onForgetKey: () -> Unit = {},
    val onNewConversation: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/** Chatting with Claude, as Ruby. Asks for an API key first if there isn't one. */
@Composable
fun ChatScreen(ui: ChatUi, context: String?, actions: ChatActions, modifier: Modifier = Modifier) {
    var changingKey by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = actions.onBack) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (ui.hasKey == true && !changingKey) {
                TextButton(onClick = actions.onNewConversation, enabled = !ui.sending && ui.turns.isNotEmpty()) {
                    Text("New chat")
                }
                TextButton(onClick = { changingKey = true }) { Text("Change key") }
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
            else -> Conversation(ui, context, actions, Modifier.weight(1f))
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
}

@Composable
private fun Conversation(ui: ChatUi, context: String?, actions: ChatActions, modifier: Modifier) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val itemCount = ui.turns.size + if (ui.replying != null) 1 else 0

    // Keep the newest message in view, including while a reply streams in.
    LaunchedEffect(itemCount, ui.replying?.length) {
        if (itemCount > 0) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE)
    }

    Text(
        "Each message goes to Anthropic's Claude along with what Ruby senses you're doing" +
            (if (context != null) " (now: $context)" else "") + ". Nothing else from the phone's sensors is sent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    Box(modifier.fillMaxWidth()) {
        if (itemCount == 0) {
            Text(
                "Ask Ruby anything. Messages and replies are saved to memory.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ui.turns) { _, turn -> Bubble(turn) }
            ui.replying?.let { soFar ->
                item { Bubble(ChatTurn(fromUser = false, text = soFar.ifEmpty { "…" })) }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Message") },
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { if (actions.onSend(draft)) draft = "" },
            enabled = draft.isNotBlank() && !ui.sending,
        ) { Text(if (ui.sending) "…" else "Send") }
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (turn.fromUser) Arrangement.End else Arrangement.Start) {
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
