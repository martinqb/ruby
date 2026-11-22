package com.martin.ruby.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.martin.ruby.memory.ArchiveSummary
import com.martin.ruby.memory.BudgetSetting
import com.martin.ruby.memory.MemoryArchive
import com.martin.ruby.memory.MemoryArchiver
import com.martin.ruby.memory.MemoryLimit
import com.martin.ruby.memory.MemoryStore
import com.martin.ruby.memory.StorageBudget
import com.martin.ruby.memory.StorageSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory's storage limit: how full it is, the setting, and the older memories that were
 * compressed or moved to Google Drive, with a button to bring each back.
 */
@Composable
fun StorageScreen(store: MemoryStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { StorageSettings(context) }
    val setting by settings.budget.collectAsState(initial = null)
    val note by settings.note.collectAsState(initial = null)
    val archives by remember(store) { store.archives() }.collectAsState(initial = emptyList())
    val working by remember {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(MemoryLimit.WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
    }.collectAsState(initial = false)

    var usage by remember { mutableStateOf<MemoryLimit.Usage?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            usage = runCatching { MemoryLimit.usage(context) }.getOrNull()
            delay(2_000)
        }
    }

    var busyId by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var askSecretFor by remember { mutableStateOf<Long?>(null) }

    fun bringBack(id: Long, secret: String? = null) {
        busyId = id
        message = null
        scope.launch {
            message = try {
                val count = store.bringBack(id, secret)
                "Brought back $count messages. They're searchable again and stay on the phone for at least a week."
            } catch (e: MemoryArchiver.NeedsOldSecret) {
                askSecretFor = id
                null
            } catch (e: Exception) {
                "Couldn't bring them back: ${e.message ?: e.javaClass.simpleName}"
            }
            busyId = null
        }
    }

    fun save(new: BudgetSetting) {
        scope.launch {
            settings.setBudget(new)
            MemoryLimit.check(context)
            usage = runCatching { MemoryLimit.usage(context) }.getOrNull()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Memory storage", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        usage?.let { UsageSection(it) }

        Spacer(Modifier.height(20.dp))
        setting?.let { current -> LimitSection(current, usage?.totalBytes ?: 0L, onSave = ::save) }

        Spacer(Modifier.height(20.dp))
        Text("At the limit", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ruby never deletes memories to make room. She first compresses older messages on " +
                "the phone. If that isn't enough, she moves the oldest compressed ones to your " +
                "Google Drive, encrypted like your backups. The newest ${MemoryArchiver.KEEP_NEWEST} " +
                "messages always stay. Anything moved can be brought back below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { MemoryLimit.runSoon(context) }, enabled = !working) { Text("Check now") }
            Spacer(Modifier.width(12.dp))
            if (working) Text("Working…", style = MaterialTheme.typography.bodyMedium)
        }
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                "Last check, ${formatTime(it.timeMillis)}: ${it.text}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (it.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("Older memories", style = MaterialTheme.typography.titleMedium)
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
        }
        if (archives.isEmpty()) {
            Text(
                "None yet. Everything is in memory, and search finds all of it.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "Search doesn't look inside these. Bring a bundle back to search it again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            archives.forEach { archive ->
                ArchiveRow(archive, busy = busyId == archive.id, enabled = busyId == null) { bringBack(archive.id) }
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(24.dp))
        setting?.let { current ->
            TestingSection(
                current,
                onSave = ::save,
                onAddNotes = { scope.launch { store.addTestNotes(25) } },
                onDeleteNotes = { scope.launch { message = "Deleted ${store.deleteTestNotes()} test notes from memory." } },
                onMoveToDrive = { scope.launch { message = "Moving to Google Drive…"; message = MemoryLimit.moveOldestToDriveNow(context) } },
            )
        }
    }

    askSecretFor?.let { id ->
        var secret by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askSecretFor = null },
            title = { Text("Earlier passphrase needed") },
            text = {
                Column {
                    Text(
                        "These memories were moved to Drive before backup was set up again. Enter the " +
                            "passphrase or recovery key from then."
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it },
                        label = { Text("Passphrase or recovery key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askSecretFor = null
                    bringBack(id, secret)
                }, enabled = secret.isNotBlank()) { Text("Bring back") }
            },
            dismissButton = { TextButton(onClick = { askSecretFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UsageSection(usage: MemoryLimit.Usage) {
    val percent = (usage.fraction * 100).toInt()
    val color = when (usage.level) {
        StorageBudget.Level.OK -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    LinearProgressIndicator(
        progress = { usage.fraction.toFloat().coerceIn(0f, 1f) },
        color = color,
        modifier = Modifier.fillMaxWidth().height(8.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "${StorageBudget.describe(usage.usedBytes)} of ${StorageBudget.describe(usage.limitBytes)} ($percent%)",
        style = MaterialTheme.typography.bodyLarge,
    )
    when (usage.level) {
        StorageBudget.Level.WARN -> Text(
            "Getting full. At the limit Ruby will make room by compressing and moving older memories.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        StorageBudget.Level.FULL -> Text(
            "At the limit. Ruby is making room.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        StorageBudget.Level.OK -> {}
    }
    if (usage.squeezed && !usage.setting.tinyTest) {
        Text(
            "Your phone is nearly full, so the limit is lower than you set " +
                "(${StorageBudget.describe(usage.chosenBytes)}): Ruby always leaves " +
                "${StorageBudget.describe(StorageBudget.reserve(usage.totalBytes))} free for the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text(
        "Phone storage: ${StorageBudget.describe(usage.freeBytes)} free of ${StorageBudget.describe(usage.totalBytes)}.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LimitSection(setting: BudgetSetting, totalBytes: Long, onSave: (BudgetSetting) -> Unit) {
    var mode by remember(setting) { mutableStateOf(setting.mode) }
    var percentText by remember(setting) { mutableStateOf(formatNumber(setting.percent)) }
    val fixedUnit = if (setting.fixedBytes >= 1_000_000_000_000L) "TB" else "GB"
    var unit by remember(setting) { mutableStateOf(fixedUnit) }
    var amountText by remember(setting) {
        mutableStateOf(
            if (setting.fixedBytes > 0) {
                formatNumber(setting.fixedBytes / if (fixedUnit == "TB") 1e12 else 1e9)
            } else ""
        )
    }

    val percent = percentText.trim().replace(',', '.').toDoubleOrNull()
    val percentOk = percent != null && percent in StorageBudget.MIN_PERCENT..StorageBudget.MAX_PERCENT
    val fixed = StorageBudget.parseAmount(amountText, unit)
    val fixedOk = fixed != null && fixed >= StorageBudget.MIN_FIXED_BYTES && (totalBytes == 0L || fixed <= totalBytes)
    val new = when (mode) {
        BudgetSetting.Mode.PERCENT -> setting.copy(mode = mode, percent = percent ?: setting.percent)
        BudgetSetting.Mode.FIXED -> setting.copy(mode = mode, fixedBytes = fixed ?: setting.fixedBytes)
    }
    val valid = if (mode == BudgetSetting.Mode.PERCENT) percentOk else fixedOk

    Text("Limit", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = mode == BudgetSetting.Mode.PERCENT, onClick = { mode = BudgetSetting.Mode.PERCENT })
        Text("A share of phone storage")
    }
    if (mode == BudgetSetting.Mode.PERCENT) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
            OutlinedTextField(
                value = percentText,
                onValueChange = { percentText = it },
                label = { Text("Percent") },
                suffix = { Text("%") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                if (percentOk && totalBytes > 0) "= ${StorageBudget.describe((totalBytes * percent!! / 100).toLong())}"
                else "1 to 50%",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = mode == BudgetSetting.Mode.FIXED, onClick = { mode = BudgetSetting.Mode.FIXED })
        Text("A fixed amount")
    }
    if (mode == BudgetSetting.Mode.FIXED) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 48.dp)) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            listOf("GB", "TB").forEach { option ->
                FilterChip(selected = unit == option, onClick = { unit = option }, label = { Text(option) })
                Spacer(Modifier.width(4.dp))
            }
        }
        if (amountText.isNotBlank() && !fixedOk) {
            Text(
                "Between 0.1 GB and the phone's size (${StorageBudget.describe(totalBytes)}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSave(new) }, enabled = valid && new != setting) { Text("Save limit") }
        TextButton(
            onClick = { onSave(setting.copy(mode = BudgetSetting.Mode.PERCENT, percent = StorageBudget.DEFAULT_PERCENT)) },
            enabled = setting.mode != BudgetSetting.Mode.PERCENT || setting.percent != StorageBudget.DEFAULT_PERCENT,
        ) { Text("Back to 5%") }
    }
    if (setting.tinyTest) {
        Text(
            "The tiny test limit is on, so this setting is ignored until you turn it off below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ArchiveRow(archive: ArchiveSummary, busy: Boolean, enabled: Boolean, onBringBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "${formatTime(archive.firstMillis)} – ${formatTime(archive.lastMillis)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            val where = if (archive.state == MemoryArchive.STATE_DRIVE) "in Google Drive" else "compressed on the phone"
            Text(
                "${archive.messageCount} messages · ${StorageBudget.describe(archive.sizeBytes)} · $where",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onBringBack, enabled = enabled) { Text(if (busy) "Bringing back…" else "Bring back") }
    }
}

@Composable
private fun TestingSection(
    setting: BudgetSetting,
    onSave: (BudgetSetting) -> Unit,
    onAddNotes: () -> Unit,
    onDeleteNotes: () -> Unit,
    onMoveToDrive: () -> Unit,
) {
    Text("Testing", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tiny test limit (${StorageBudget.describe(StorageBudget.TINY_TEST_BYTES)})")
            Text(
                "Pretends memory may only use this much, so you can watch the warning, compression " +
                    "and the move to Drive without filling your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = setting.tinyTest, onCheckedChange = { onSave(setting.copy(tinyTest = it)) })
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onAddNotes) { Text("Add 25 test notes") }
        TextButton(onClick = onDeleteNotes) { Text("Delete test notes") }
    }
    Text(
        "Each is about 600 characters, marked \"Test note\". \"Delete test notes\" removes the ones in memory; " +
            "compressed ones stay in their bundles until you bring them back.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onMoveToDrive) { Text("Move oldest bundle to Drive now") }
    Text(
        "Does what the limit does when compressing isn't enough, without waiting for it. Needs backup " +
            "set up and Drive approved.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

/** 5.0 → "5", 2.5 → "2.5". */
private fun formatNumber(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else "%.2f".format(Locale.US, value).trimEnd('0').trimEnd('.')
