package com.martin.ruby.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.martin.ruby.memory.MemoryArchive
import com.martin.ruby.memory.MemoryExport
import com.martin.ruby.memory.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

/**
 * Export: saves everything in memory to a file the user picks, as readable text or as JSON.
 * Memory itself doesn't change.
 */
@Composable
fun ExportScreen(store: MemoryStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val count by remember(store) { store.count() }.collectAsState(initial = 0)
    val archives by remember(store) { store.archives() }.collectAsState(initial = emptyList())
    val onPhone = archives.filter { it.state == MemoryArchive.STATE_PHONE }.sumOf { it.messageCount }
    val inDrive = archives.filter { it.state == MemoryArchive.STATE_DRIVE }.sumOf { it.messageCount }

    var format by rememberSaveable { mutableStateOf(MemoryExport.Format.TEXT) }
    var includeDrive by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<MemoryExport.Summary?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun export(uri: Uri, chosen: MemoryExport.Format) {
        scope.launch {
            busy = true
            progress = 0
            result = null
            failure = null
            try {
                result = withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException("couldn't open the file for writing")
                    stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                        store.export(writer, chosen, includeDrive && inDrive > 0) { progress = it }
                    }
                }
            } catch (e: CancellationException) {
                // Left the screen part way: don't leave half a file behind.
                withContext(NonCancellable) { deletePartial(context, uri) }
                throw e
            } catch (e: Exception) {
                deletePartial(context, uri)
                failure = "Export failed: ${e.message ?: e.javaClass.simpleName}. Nothing in memory changed."
            } finally {
                busy = false
            }
        }
    }

    val saveText = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoryExport.Format.TEXT.mimeType)
    ) { uri -> uri?.let { export(it, MemoryExport.Format.TEXT) } }
    val saveJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MemoryExport.Format.JSON.mimeType)
    ) { uri -> uri?.let { export(it, MemoryExport.Format.JSON) } }

    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
        Text("Export memory", style = MaterialTheme.typography.headlineSmall)
        Text(
            whatsIncluded(count, onPhone, inDrive),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Text("Format", style = MaterialTheme.typography.titleMedium)
        FormatOption(
            selected = format == MemoryExport.Format.TEXT,
            enabled = !busy,
            title = "Readable text (.txt)",
            detail = "Grouped by day, easy to read or print.",
            onSelect = { format = MemoryExport.Format.TEXT },
        )
        FormatOption(
            selected = format == MemoryExport.Format.JSON,
            enabled = !busy,
            title = "JSON (.json)",
            detail = "Every detail, for other apps. Also has your saved places and your car.",
            onSelect = { format = MemoryExport.Format.JSON },
        )

        if (inDrive > 0) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include memories moved to Google Drive", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Downloads them just for this export. They stay in Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = includeDrive, onCheckedChange = { includeDrive = it }, enabled = !busy)
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "The exported file isn't encrypted. Anyone who can open it can read your memories, " +
                    "so save it somewhere private.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val name = MemoryExport.fileName(format)
                if (format == MemoryExport.Format.TEXT) saveText.launch(name) else saveJson.launch(name)
            },
            enabled = !busy,
        ) { Text("Export…") }

        if (busy) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "Exporting… ${number(progress)} messages so far. Keep this screen open.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        result?.let { summary ->
            Spacer(Modifier.height(16.dp))
            Text(describe(summary), style = MaterialTheme.typography.bodyMedium)
            if (summary.skipped.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Not included:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                summary.skipped.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        failure?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FormatOption(selected: Boolean, enabled: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "1,240 messages in memory, 300 compressed on this phone and 64 moved to Google Drive." */
private fun whatsIncluded(count: Int, onPhone: Int, inDrive: Int): String {
    val parts = mutableListOf(messages(count) + " in memory")
    if (onPhone > 0) parts += "${number(onPhone)} compressed on this phone"
    if (inDrive > 0) parts += "${number(inDrive)} moved to Google Drive"
    return when (parts.size) {
        1 -> parts[0]
        2 -> parts.joinToString(" and ")
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    } + "."
}

/** "Exported 1,240 messages: 900 from memory, 300 compressed on this phone, 40 from Google Drive." */
private fun describe(summary: MemoryExport.Summary): String {
    val parts = listOfNotNull(
        summary.fromMemory.takeIf { it > 0 }?.let { "${number(it)} from memory" },
        summary.fromPhoneArchive.takeIf { it > 0 }?.let { "${number(it)} compressed on this phone" },
        summary.fromDrive.takeIf { it > 0 }?.let { "${number(it)} from Google Drive" },
    )
    val start = "Exported ${messages(summary.total)}"
    return if (parts.size > 1) "$start: ${parts.joinToString(", ")}." else "$start."
}

private fun messages(count: Int) = if (count == 1) "1 message" else "${number(count)} messages"

private fun number(value: Int): String = NumberFormat.getIntegerInstance().format(value)

/** Removes a file that was only partly written. */
private suspend fun deletePartial(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
}
