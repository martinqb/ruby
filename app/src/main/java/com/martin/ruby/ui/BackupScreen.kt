package com.martin.ruby.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.martin.ruby.backup.BackupCrypto
import com.martin.ruby.backup.BackupManager
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore. For now a backup is a file the user saves where they like (Google
 * Drive comes next); restoring from a file is how a new phone gets its memory back.
 */
@Composable
fun BackupScreen(manager: BackupManager, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var setUp by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf(false) }

    // Restoring: the chosen file, then the unlocked backup waiting for confirmation.
    var restoreFile by remember { mutableStateOf<ChosenFile?>(null) }
    var unlocked by remember { mutableStateOf<BackupManager.Unlocked?>(null) }

    LaunchedEffect(manager) { setUp = manager.isSetUp() }

    fun run(what: String, block: suspend () -> Unit) {
        busy = what
        message = null
        scope.launch {
            try {
                block()
            } catch (e: BackupCrypto.CantOpen) {
                message = e.message
            } catch (e: Exception) {
                message = "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = null
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Making the backup…") {
            val backup = manager.makeBackup()
            writeFile(context, uri, backup.bytes)
            message = "Backup saved: ${backup.messageCount} messages, ${sizeLabel(backup.bytes.size)}, " +
                "encrypted. Keep a copy off this phone."
        }
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Reading the file…") {
            restoreFile = readFile(context, uri)
            unlocked = null
        }
    }

    // While the recovery key is showing, Back can't be used to skip past it by accident.
    recoveryKey?.let { key ->
        BackHandler {}
        RecoveryKeyContent(
            recoveryKey = key,
            onCopy = { copySensitive(context, key) },
            onDone = {
                recoveryKey = null
                message = "Backup is set up. Your passphrase or your recovery key will unlock it."
            },
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        TextButton(onClick = onBack, enabled = busy == null) { Text("Back") }
        Text("Backup and restore", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Backups are compressed and encrypted on this phone before they leave it. Only your " +
                "passphrase or your recovery key can open them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        when {
            setUp == null -> Unit
            setUp == false || changing -> SetUpSection(
                changing = changing,
                busy = busy != null,
                onSetUp = { passphrase ->
                    run("Setting up (this takes a few seconds)…") {
                        recoveryKey = manager.setUp(passphrase)
                        setUp = true
                        changing = false
                    }
                },
                onCancel = { changing = false },
            )
            else -> {
                Text("Back up now", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Saves an encrypted backup file. Put it somewhere other than this phone, like " +
                        "your computer. (Automatic backups to your Google Drive come next.)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { saveLauncher.launch(backupFileName()) }, enabled = busy == null) {
                    Text("Save a backup file")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { changing = true }, enabled = busy == null) {
                    Text("Change passphrase and recovery key")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text("Restore", style = MaterialTheme.typography.titleMedium)
        Text(
            "Replaces the memory on this phone with a backup. Use this on a new phone, or after " +
                "reinstalling Ruby.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = busy == null) {
            Text(if (restoreFile == null) "Choose a backup file" else "Choose a different file")
        }
        restoreFile?.let { file ->
            Spacer(Modifier.height(12.dp))
            Text("Chosen file", style = MaterialTheme.typography.labelMedium)
            Text(describeFile(file), style = MaterialTheme.typography.bodyMedium)
            if (unlocked == null) {
                UnlockSection(
                    busy = busy != null,
                    onUnlock = { secret ->
                        run("Unlocking (this takes a few seconds)…") { unlocked = manager.unlock(file.bytes, secret) }
                    },
                )
            }
        }
        unlocked?.let { backup ->
            Spacer(Modifier.height(12.dp))
            Text(
                "Unlocked: a backup from ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.createdMillis))} " +
                    "with ${backup.messageCount} messages.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            var confirm by remember { mutableStateOf(false) }
            Button(onClick = { confirm = true }, enabled = busy == null) { Text("Restore this backup") }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Replace memory?") },
                    text = {
                        Text(
                            "Everything in memory on this phone will be replaced by this backup's " +
                                "${backup.messageCount} messages. From now on, backups use that backup's " +
                                "passphrase and recovery key."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirm = false
                            run("Restoring…") {
                                val count = manager.restore(backup)
                                restoreFile = null
                                unlocked = null
                                setUp = true
                                changing = false
                                message = "Restored $count messages."
                            }
                        }) { Text("Replace memory", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
        }

        busy?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Choosing the passphrase, typed twice. */
@Composable
private fun SetUpSection(changing: Boolean, busy: Boolean, onSetUp: (String) -> Unit, onCancel: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = passphrase.length < BackupCrypto.MIN_PASSPHRASE_LENGTH
    val mismatch = again.isNotEmpty() && again != passphrase

    Text(if (changing) "Choose a new passphrase" else "Choose a passphrase", style = MaterialTheme.typography.titleMedium)
    Text(
        (if (changing) "Backups made from now on use the new passphrase and a new recovery key. " +
            "Older backups still open with the old ones. " else "") +
            "Use at least ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters. A few unrelated words are " +
            "easy to remember and hard to guess. Next, Ruby will give you a recovery key as a spare.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    PassphraseField(passphrase, { passphrase = it }, "Passphrase")
    PassphraseField(again, { again = it }, "Type it again")
    when {
        passphrase.isNotEmpty() && tooShort -> Hint("${passphrase.length} of ${BackupCrypto.MIN_PASSPHRASE_LENGTH} characters.")
        mismatch -> Hint("The two don't match.")
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { onSetUp(passphrase) }, enabled = !busy && !tooShort && again == passphrase) {
            Text("Set up backup")
        }
        if (changing) TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
    }
}

/** Typing the passphrase or the recovery key to open a backup. */
@Composable
private fun UnlockSection(busy: Boolean, onUnlock: (String) -> Unit) {
    var secret by remember { mutableStateOf("") }
    Spacer(Modifier.height(12.dp))
    PassphraseField(secret, { secret = it }, "Passphrase or recovery key")
    Spacer(Modifier.height(8.dp))
    Button(onClick = { onUnlock(secret) }, enabled = !busy && secret.isNotBlank()) { Text("Unlock") }
}

@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** The recovery key, shown once, with the warning. Done only works after ticking the box. */
@Composable
private fun RecoveryKeyContent(
    recoveryKey: String,
    onCopy: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var saved by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Your recovery key", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Save this somewhere safe now",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ruby shows this key only once and doesn't keep a copy. Write it on paper you " +
                        "keep at home, or save it in a password manager. Don't keep it only on this phone.\n\n" +
                        "If you forget your passphrase and lose this key, your backups can't be opened " +
                        "by anyone: not you, not Ruby, not Google.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            // Two rows of four groups, easy to copy by hand.
            recoveryKey.split("-").chunked(4).joinToString("\n") { it.joinToString("-") },
            fontFamily = FontFamily.Monospace,
            fontSize = 22.sp,
            lineHeight = 34.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCopy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Copy (for a password manager)")
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = { saved = it })
            Text("I've saved my recovery key somewhere safe.", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

// Helpers

private fun backupFileName(): String =
    "ruby-memory-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".rubybackup"

private fun sizeLabel(bytes: Int): String = when {
    bytes < 1024 -> "$bytes bytes"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}

private suspend fun writeFile(context: Context, uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        ?: error("Couldn't open the file for writing.")
}

/** A backup file the user picked: what it's called and when it's from, plus its bytes. */
private class ChosenFile(val name: String?, val dateMillis: Long?, val bytes: ByteArray)

private suspend fun readFile(context: Context, uri: Uri): ChosenFile = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Couldn't open the file.")
    var name: String? = null
    var modified: Long? = null
    // No column list: some storage apps refuse a query that names a column they lack.
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                    ?.let { if (!cursor.isNull(it)) modified = cursor.getLong(it).takeIf { ms -> ms > 0 } }
            }
        }
    }
    ChosenFile(name, modified ?: dateFromName(name), bytes)
}

/** Ruby names backups like "ruby-memory-2026-11-22-1432.rubybackup"; reads the date from that. */
private fun dateFromName(name: String?): Long? {
    val stamp = name?.let { Regex("""(\d{4}-\d{2}-\d{2}-\d{4})""").find(it)?.value } ?: return null
    return runCatching { SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).parse(stamp)?.time }.getOrNull()
}

private fun describeFile(file: ChosenFile): String {
    val date = file.dateMillis?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: "date unknown"
    return listOf(file.name ?: "Unnamed file", date, sizeLabel(file.bytes.size)).joinToString(" · ")
}

/**
 * Copies the recovery key, marked sensitive so Android 13+ hides it in the clipboard
 * preview. (ClipDescription.EXTRA_IS_SENSITIVE; spelled out because older Androids lack it.)
 */
private fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("Ruby recovery key", text).apply {
        description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

@Preview(showBackground = true)
@Composable
private fun RecoveryKeyPreview() {
    RubyTheme {
        RecoveryKeyContent(
            recoveryKey = "7KQ2-MV9D-X4PA-3HNW-RB8T-0ZCE-5JYF-6GSD",
            onCopy = {},
            onDone = {},
        )
    }
}
