package com.martin.ruby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.martin.ruby.memory.ArchiveSummary
import com.martin.ruby.memory.MemoryArchive
import com.martin.ruby.memory.MemoryLimit
import com.martin.ruby.memory.MemoryMessage
import com.martin.ruby.memory.StorageBudget
import com.martin.ruby.memory.MemoryStore
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Everything in Ruby's memory: see it, search it, add to it and delete it.
 *
 * Chatting with Claude comes later; until then, notes typed here are saved the same way
 * chat messages will be, so saving, search and (soon) backup can be tested.
 */
@Composable
fun MemoryScreen(
    store: MemoryStore,
    onOpenBackup: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenExport: () -> Unit,
    onBack: () -> Unit,
    onOpenSummaries: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) { error = store.openError() }

    val messagesFlow = remember(store, query) {
        if (query.isBlank()) store.recent() else store.search(query)
    }
    val messages by messagesFlow.collectAsState(initial = emptyList())
    val count by remember(store) { store.count() }.collectAsState(initial = 0)
    val archives by remember(store) { store.archives() }.collectAsState(initial = emptyList())
    val appContext = LocalContext.current.applicationContext
    var usage by remember { mutableStateOf<MemoryLimit.Usage?>(null) }
    LaunchedEffect(count, archives) { usage = runCatching { MemoryLimit.usage(appContext) }.getOrNull() }

    MemoryContent(
        messages = messages,
        count = count,
        storage = usage?.let { storageLine(it, archives) },
        archivedOnPhone = archives.filter { it.state == MemoryArchive.STATE_PHONE }.sumOf { it.messageCount },
        archivedInDrive = archives.filter { it.state == MemoryArchive.STATE_DRIVE }.sumOf { it.messageCount },
        query = query,
        error = error,
        onQueryChange = { query = it },
        onAdd = { text -> scope.launch { store.add(MemoryMessage.ROLE_USER, text) } },
        onDelete = { message -> scope.launch { store.delete(message) } },
        onDeleteAll = { scope.launch { store.deleteAll() } },
        onOpenBackup = onOpenBackup,
        onOpenStorage = onOpenStorage,
        onOpenExport = onOpenExport,
        onBack = onBack,
        onOpenSummaries = onOpenSummaries,
        modifier = modifier,
    )
}

@Composable
private fun MemoryContent(
    messages: List<MemoryMessage>,
    count: Int,
    storage: String?,
    archivedOnPhone: Int,
    archivedInDrive: Int,
    query: String,
    error: String?,
    onQueryChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (MemoryMessage) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenExport: () -> Unit,
    onBack: () -> Unit,
    onOpenSummaries: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            TextButton(onClick = onBack) { Text("Back") }
            // Wraps onto a second line on narrow phones.
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenStorage) { Text("Storage") }
                TextButton(onClick = onOpenSummaries) { Text("Summaries") }
                TextButton(onClick = onOpenExport) { Text("Export") }
                TextButton(onClick = onOpenBackup) { Text("Backup and restore") }
            }
        }
        Text("Memory", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Saved on this phone, encrypted. " +
                if (count == 1) "1 message." else "$count messages.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        storage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (query.isNotBlank() && archivedOnPhone + archivedInDrive > 0) {
            Text(
                "Search doesn't include ${archivedOnPhone + archivedInDrive} archived older messages. " +
                    "Bring them back on the Storage screen to search them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Memory couldn't be opened: $error",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add a note to memory") },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onAdd(draft.trim())
                    draft = ""
                },
                enabled = draft.isNotBlank() && error == null,
            ) { Text("Save") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search memory") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        when {
            messages.isNotEmpty() -> LazyColumn(Modifier.weight(1f)) {
                items(messages, key = { it.id }) { message ->
                    ListItem(
                        overlineContent = { Text(describe(message)) },
                        headlineContent = { Text(message.text) },
                        trailingContent = {
                            TextButton(onClick = { onDelete(message) }) { Text("Delete") }
                        },
                    )
                    HorizontalDivider()
                }
            }
            query.isNotBlank() -> Text("Nothing matches \"$query\".", style = MaterialTheme.typography.bodyLarge)
            else -> Text("Nothing saved yet.", style = MaterialTheme.typography.bodyLarge)
        }

        if (count > 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmDeleteAll = true }) {
                Text("Delete everything", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all memory?") },
            text = {
                Text(
                    buildString {
                        append("All $count messages")
                        if (archivedOnPhone > 0) append(", and $archivedOnPhone compressed older ones,")
                        append(" will be deleted from this phone. This can't be undone.")
                        if (archivedInDrive > 0) {
                            append(" The $archivedInDrive older messages moved to Google Drive are kept there.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onDeleteAll()
                }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

/** "21 Nov 2026, 14:32 · You · Driving" */
private fun describe(message: MemoryMessage): String {
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(message.timeMillis))
    val who = if (message.role == MemoryMessage.ROLE_RUBY) "Ruby" else "You"
    return listOfNotNull(time, who, message.context).joinToString(" · ")
}

@Preview(showBackground = true)
@Composable
private fun MemoryScreenPreview() {
    RubyTheme {
        MemoryContent(
            messages = listOf(
                MemoryMessage(2, 1_700_000_100_000, MemoryMessage.ROLE_USER, "Remember the tyre pressure is 36 psi.", "At Home"),
                MemoryMessage(1, 1_700_000_000_000, MemoryMessage.ROLE_USER, "Parked on level 3, row C.", "Driving"),
            ),
            count = 2,
            storage = "Storage: 12 KB of 12.8 GB limit (0%).",
            archivedOnPhone = 0,
            archivedInDrive = 0,
            query = "",
            error = null,
            onQueryChange = {},
            onAdd = {},
            onDelete = {},
            onDeleteAll = {},
            onOpenBackup = {},
            onOpenStorage = {},
            onOpenExport = {},
            onBack = {},
        )
    }
}

/** "Storage: 180 KB of 256 KB limit (70%) · 230 older messages archived." */
private fun storageLine(usage: MemoryLimit.Usage, archives: List<ArchiveSummary>): String {
    val percent = (usage.fraction * 100).toInt()
    val archived = archives.sumOf { it.messageCount }
    return "Storage: ${StorageBudget.describe(usage.usedBytes)} of ${StorageBudget.describe(usage.limitBytes)} " +
        "limit ($percent%)" + if (archived > 0) " · $archived older messages archived." else "."
}
