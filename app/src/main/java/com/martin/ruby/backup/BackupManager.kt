package com.martin.ruby.backup

import android.content.Context
import com.martin.ruby.log.SensingLog
import com.martin.ruby.memory.MemoryDatabase
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.memory.MemoryKey
import com.martin.ruby.security.KeystoreBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sets up backups, makes them, and restores them. Where a backup goes (a file now, the
 * user's Google Drive next) is up to the caller; this only deals in bytes.
 *
 * On the phone it keeps:
 *  - the backup key, wrapped by the Keystore, so scheduled backups don't need the passphrase;
 *  - the backup header, which holds the passphrase- and recovery-locked copies of that key.
 * The passphrase and the recovery key themselves are never stored.
 */
class BackupManager(context: Context) {

    private val appContext = context.applicationContext
    private val keyFile get() = File(appContext.noBackupFilesDir, KEY_FILE)
    private val headerFile get() = File(appContext.noBackupFilesDir, HEADER_FILE)

    /** True once a passphrase has been chosen on this phone (or adopted from a restore). */
    suspend fun isSetUp(): Boolean = withContext(Dispatchers.IO) { keyFile.exists() && headerFile.exists() }

    /**
     * Sets up backups with [passphrase] and returns the new recovery key, to show once.
     * Replaces any earlier setup: backups made before still open with the old passphrase
     * and recovery key, new ones only with these.
     */
    suspend fun setUp(passphrase: String): String {
        val setup = withContext(Dispatchers.Default) { BackupCrypto.setUp(passphrase) }
        withContext(Dispatchers.IO) { keep(setup.backupKey, setup.header) }
        SensingLog.add("Backup set up with a passphrase and a recovery key.")
        return setup.recoveryKey
    }

    /** Makes a complete, encrypted backup of memory. */
    suspend fun makeBackup(): Backup {
        val (backupKey, header) = withContext(Dispatchers.IO) {
            KeystoreBox.load(WRAP_ALIAS, keyFile) to headerFile.readBytes()
        }
        val snapshot = MemoryDatabase.snapshot(appContext)
        val bytes = withContext(Dispatchers.Default) {
            val contents = BackupContents(
                databaseKey = withContext(Dispatchers.IO) { MemoryKey.get(appContext) },
                createdMillis = System.currentTimeMillis(),
                messageCount = snapshot.messageCount,
                database = snapshot.database,
                wal = snapshot.wal,
            )
            BackupCrypto.seal(header, backupKey, contents.pack())
        }
        return Backup(bytes, snapshot.messageCount)
    }

    class Backup(val bytes: ByteArray, val messageCount: Int)

    /**
     * Encrypts other data (memories moved to Drive by the storage limit) the same way as a
     * backup, so the passphrase or recovery key can open it on a new phone too.
     */
    suspend fun seal(contents: ByteArray): ByteArray {
        val (backupKey, header) = withContext(Dispatchers.IO) {
            KeystoreBox.load(WRAP_ALIAS, keyFile) to headerFile.readBytes()
        }
        return withContext(Dispatchers.Default) { BackupCrypto.seal(header, backupKey, contents) }
    }

    /**
     * Opens something [seal] made. With no [secret], uses the key on this phone; that fails
     * with [BackupCrypto.CantOpen] if it was sealed under an earlier passphrase, and then
     * the old passphrase or recovery key opens it.
     */
    suspend fun openSealed(sealed: ByteArray, secret: String? = null): ByteArray {
        if (secret != null) return withContext(Dispatchers.Default) { BackupCrypto.open(sealed, secret.trim()).contents }
        val backupKey = withContext(Dispatchers.IO) { KeystoreBox.load(WRAP_ALIAS, keyFile) }
        return withContext(Dispatchers.Default) { BackupCrypto.openWithKey(sealed, backupKey) }
    }

    /** A backup that's been unlocked and checked, waiting for the user to confirm. */
    class Unlocked internal constructor(
        val createdMillis: Long,
        val messageCount: Int,
        internal val opened: BackupCrypto.Opened,
        internal val contents: BackupContents,
    )

    /**
     * Unlocks a backup with the passphrase or the recovery key, and checks it's complete.
     * Nothing on the phone changes yet. Throws [BackupCrypto.CantOpen] if it can't.
     */
    suspend fun unlock(backup: ByteArray, secret: String): Unlocked = withContext(Dispatchers.Default) {
        val opened = BackupCrypto.open(backup, secret.trim())
        val contents = BackupContents.unpack(opened.contents)
        Unlocked(contents.createdMillis, contents.messageCount, opened, contents)
    }

    /**
     * Replaces memory on this phone with an unlocked backup. From then on, backups use
     * that backup's passphrase and recovery key. Returns the number of messages restored.
     *
     * It can't be cancelled once started: stopping between replacing memory and keeping the
     * backup's key would leave memory restored but later backups using the wrong key. Use
     * [RestoreSession] to run it, so leaving the screen doesn't matter either.
     */
    suspend fun restore(unlocked: Unlocked): Int = withContext(NonCancellable) {
        val contents = unlocked.contents
        SensingLog.add("Restoring memory from a backup with ${unlocked.messageCount} messages…")
        val count = MemoryDatabase.replace(appContext, contents.database, contents.wal, contents.databaseKey)
        withContext(Dispatchers.IO) { keep(unlocked.opened.backupKey, unlocked.opened.header) }
        SensingLog.add("Memory restored from a backup: $count messages.")
        // The backup brought its reminders along; Android needs their alarms set again.
        runCatching { ReminderStore(appContext).rescheduleAll() }
            .onFailure { SensingLog.add("Couldn't schedule restored reminders: ${it.message}") }
        // An older backup has an older overview (or none): bring her project knowledge up to date.
        runCatching { com.martin.ruby.memory.ProjectKnowledge.sync(appContext) }
            .onFailure { SensingLog.add("Couldn't update project knowledge after restoring: ${it.message}") }
        count
    }

    private fun keep(backupKey: ByteArray, header: ByteArray) {
        KeystoreBox.save(WRAP_ALIAS, keyFile, backupKey)
        headerFile.writeBytes(header)
    }

    private companion object {
        const val WRAP_ALIAS = "ruby_backup_wrap"
        const val KEY_FILE = "backup_key.bin"
        const val HEADER_FILE = "backup_header.bin"
    }
}

/** The name Ruby gives backups, like "ruby-memory-2026-11-22-1432.rubybackup". */
fun backupFileName(now: Date = Date()): String =
    "ruby-memory-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(now) + ".rubybackup"
