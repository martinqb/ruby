package com.martin.ruby.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
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
import com.martin.ruby.backup.BackupStatus
import com.martin.ruby.backup.BackupStatusStore
import com.martin.ruby.backup.BackupHistory
import com.martin.ruby.backup.BackupRecord
import kotlinx.coroutines.CancellationException
import com.martin.ruby.backup.NightlyBackup
import com.martin.ruby.backup.backupFileName
import com.martin.ruby.drive.DriveAuth
import com.martin.ruby.drive.DriveBackup
import com.martin.ruby.drive.DriveClient
import com.martin.ruby.drive.DriveFile
import com.martin.ruby.drive.DriveRetention
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup and restore. A backup goes to the user's Google Drive (a private folder only Ruby
 * can see) or to a file they save; restoring from either is how a new phone gets its
 * memory back.
 */
@Composable
fun BackupScreen(manager: BackupManager, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drive = remember(manager) { DriveBackup(context, manager) }
    val statusStore = remember { BackupStatusStore(context) }
    val status by statusStore.status.collectAsState(initial = BackupStatus())
    val history = remember { BackupHistory(context) }
    val records by history.records.collectAsState(initial = emptyList())
    /** The backups in Drive, once listed. */
    var driveFiles by remember { mutableStateOf<List<DriveFile>?>(null) }

    var setUp by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf(false) }

    // Restoring: the chosen file, then the unlocked backup waiting for confirmation.
    var restoreFile by remember { mutableStateOf<ChosenFile?>(null) }
    var unlocked by remember { mutableStateOf<BackupManager.Unlocked?>(null) }

    LaunchedEffect(manager) { setUp = manager.isSetUp() }

    /** Runs [block], showing [what] meanwhile. If it fails, the reason is shown and passed to [onFail]. */
    fun run(what: String, onFail: (suspend (String) -> Unit)? = null, block: suspend () -> Unit) {
        busy = what
        message = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = failureText(e)
                message = why
                onFail?.invoke(why)
            } finally {
                busy = null
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Making the backup…", onFail = { history.failed(BackupRecord.Kind.MANUAL_FILE, it) }) {
            val backup = manager.makeBackup()
            writeFile(context, uri, backup.bytes)
            history.succeeded(BackupRecord.Kind.MANUAL_FILE, backup.messageCount, backup.bytes.size.toLong())
            message = "Backup saved: ${backup.messageCount} messages, ${sizeLabel(backup.bytes.size)}, " +
                "encrypted. Keep a copy off this phone."
        }
    }

    // Google Drive: get a token (asking the user the first time), then run the action.
    var afterConsent by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }
    var afterConsentLabel by remember { mutableStateOf("") }
    var afterConsentFail by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = afterConsent
        afterConsent = null
        if (action == null) return@rememberLauncherForActivityResult
        val onFail = afterConsentFail
        afterConsentFail = null
        if (result.resultCode != Activity.RESULT_OK) {
            message = "Google Drive access wasn't allowed."
            onFail?.let { scope.launch { it("Google Drive access wasn't allowed.") } }
            return@rememberLauncherForActivityResult
        }
        run(afterConsentLabel, onFail) { action(DriveAuth.tokenFromIntent(context, result.data)) }
    }

    fun withDrive(what: String, onFail: (suspend (String) -> Unit)? = null, action: suspend (token: String) -> Unit) {
        run(what, onFail) {
            when (val auth = DriveAuth.authorize(context)) {
                is DriveAuth.Result.Token -> action(auth.accessToken)
                is DriveAuth.Result.NeedsConsent -> {
                    afterConsent = action
                    afterConsentLabel = what
                    afterConsentFail = onFail
                    consentLauncher.launch(IntentSenderRequest.Builder(auth.pendingIntent).build())
                }
            }
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
                if (status.needsApproval) {
                    ApprovalBanner(
                        busy = busy != null,
                        onApprove = {
                            withDrive("Approving and backing up…") { token ->
                                val uploaded = drive.backUp(token)
                                NightlyBackup.clearApprovalNotice(context)
                                message = "Approved. Backed up ${uploaded.messageCount} messages; nightly backups will carry on."
                            }
                        },
                    )
                    Spacer(Modifier.height(16.dp))
                }
                NightlySection(
                    status = status,
                    busy = busy != null,
                    onNightlyChange = { on ->
                        if (on) {
                            // Asks for Drive access now, while the user is here, not at 2 AM.
                            withDrive("Checking Google Drive access…") {
                                statusStore.setNightlyOn(true)
                                NightlyBackup.schedule(context)
                                message = "Nightly backups are on. They run while the phone charges on Wi-Fi."
                            }
                        } else {
                            scope.launch {
                                statusStore.setNightlyOn(false)
                                NightlyBackup.cancel(context)
                            }
                            message = "Nightly backups are off."
                        }
                    },
                    onTestNow = {
                        NightlyBackup.runTestNow(context)
                        message = "Nightly backup started in the background. The status above updates when it finishes."
                    },
                )
                Spacer(Modifier.height(24.dp))
                Text("Back up now", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Google Drive: goes to a private folder in your Drive that only Ruby can see. " +
                        "Ruby keeps the newest ${DriveRetention.KEEP} there.\n" +
                        "File: saves a backup file wherever you choose, like your computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            withDrive(
                                "Backing up to Google Drive…",
                                onFail = { history.failed(BackupRecord.Kind.MANUAL_DRIVE, it) },
                            ) { token ->
                                val uploaded = drive.backUp(token)
                                history.succeeded(BackupRecord.Kind.MANUAL_DRIVE, uploaded.messageCount, uploaded.file.sizeBytes)
                                if (driveFiles != null) driveFiles = drive.list(token)
                                message = "Backed up to Google Drive: ${uploaded.messageCount} messages, " +
                                    sizeLabel(uploaded.file.sizeBytes.toInt()) + "." +
                                    if (uploaded.deleted > 0) " Removed ${uploaded.deleted} older backups." else ""
                            }
                        },
                        enabled = busy == null,
                    ) { Text("Google Drive") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { saveLauncher.launch(backupFileName()) }, enabled = busy == null) {
                        Text("File")
                    }
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
        Row {
            OutlinedButton(
                onClick = { withDrive("Looking in Google Drive…") { token -> driveFiles = drive.list(token) } },
                enabled = busy == null,
            ) { Text("From Google Drive") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = busy == null) {
                Text("From a file")
            }
        }
        driveFiles?.let { files ->
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Text("No backups in your Google Drive yet.", style = MaterialTheme.typography.bodyMedium)
            }
            files.forEach { file ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.modifiedMillis)) +
                            " · " + sizeLabel(file.sizeBytes.toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            withDrive("Downloading the backup…") { token ->
                                restoreFile = ChosenFile(file.name, file.modifiedMillis, drive.download(token, file))
                                unlocked = null
                                driveFiles = null
                            }
                        },
                        enabled = busy == null,
                    ) { Text("Use this") }
                }
            }
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

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        HistorySection(records)
    }
}

/** The last [BackupHistory.KEEP] backups, newest first. */
@Composable
private fun HistorySection(records: List<BackupRecord>) {
    Text("Backup history", style = MaterialTheme.typography.titleMedium)
    Text(
        "The last ${BackupHistory.KEEP} backups, newest first.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    if (records.isEmpty()) {
        Text(
            "No backups recorded yet. Backups made before this update aren't listed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    records.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                "${format.format(Date(r.timeMillis))} · ${r.kind.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (r.succeeded) "Succeeded: ${r.detail}" else "Failed: ${r.detail}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (r.succeeded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider()
    }
}

/** The message shown (and kept in the history) when a backup action fails. */
private fun failureText(e: Exception): String = when (e) {
    is BackupCrypto.CantOpen, is DriveAuth.Failed, is DriveClient.Error -> e.message ?: e.javaClass.simpleName
    is java.io.IOException -> "Couldn't reach Google Drive: ${e.message ?: "check the internet connection"}."
    else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
}

/** "Google needs you to approve again": why backups stopped, and the button that fixes it. */
@Composable
private fun ApprovalBanner(busy: Boolean, onApprove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Backups are paused",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Google needs you to approve Ruby's access to your Google Drive again. Until then, " +
                    "nightly backups can't run. Your memory on this phone is fine.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onApprove, enabled = !busy) { Text("Approve") }
        }
    }
}

/** The nightly backup switch, how the last backup went, and a button to test it now. */
@Composable
private fun NightlySection(
    status: BackupStatus,
    busy: Boolean,
    onNightlyChange: (Boolean) -> Unit,
    onTestNow: () -> Unit,
) {
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Nightly backup to Google Drive", style = MaterialTheme.typography.titleMedium)
            Text(
                "Once a night while the phone is charging on Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = status.nightlyOn, onCheckedChange = onNightlyChange, enabled = !busy)
    }
    Spacer(Modifier.height(8.dp))
    Text(
        status.lastSuccessMillis?.let {
            "Last backup: ${dateTime.format(Date(it))} · ${status.lastMessageCount} messages · " +
                sizeLabel(status.lastSizeBytes.toInt())
        } ?: "No backup to Google Drive yet.",
        style = MaterialTheme.typography.bodyMedium,
    )
    val failedAt = status.lastFailureMillis
    if (failedAt != null && failedAt > (status.lastSuccessMillis ?: 0L) && !status.needsApproval) {
        Text(
            "Last try failed (${dateTime.format(Date(failedAt))}): ${status.lastError}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (status.nightlyOn) {
        TextButton(onClick = onTestNow, enabled = !busy) { Text("Test the nightly backup now") }
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
