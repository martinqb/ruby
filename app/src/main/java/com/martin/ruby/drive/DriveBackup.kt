package com.martin.ruby.drive

import android.content.Context
import com.martin.ruby.backup.BackupManager
import com.martin.ruby.backup.backupFileName
import com.martin.ruby.log.SensingLog

/**
 * Backups to the user's Google Drive: upload one now, list them, download one to restore.
 * Each call takes a token from [DriveAuth]; if Drive says it has expired, it's refreshed
 * once, silently, and the call is tried again.
 *
 * Only already-encrypted backups (from [BackupManager]) ever leave the phone.
 */
class DriveBackup(context: Context, private val manager: BackupManager) {

    private val appContext = context.applicationContext

    class Uploaded(val file: DriveFile, val messageCount: Int, val deleted: Int)

    /** Makes a backup, uploads it, then deletes all but the newest [DriveRetention.KEEP]. */
    suspend fun backUp(token: String): Uploaded {
        val backup = manager.makeBackup()
        val name = backupFileName()
        return withDrive(token) { drive ->
            val file = drive.upload(name, backup.bytes)
            // Only after the new one is safely up. A failed delete isn't worth failing over.
            val old = runCatching { DriveRetention.toDelete(drive.list()) }.getOrDefault(emptyList())
            val deleted = old.count { runCatching { drive.delete(it.id) }.isSuccess }
            SensingLog.add(
                "Backed up to Google Drive: ${backup.messageCount} messages, ${backup.bytes.size / 1024} KB" +
                    (if (deleted > 0) ", removed $deleted older." else ".")
            )
            Uploaded(file, backup.messageCount, deleted)
        }
    }

    /** The backups in Drive, newest first. */
    suspend fun list(token: String): List<DriveFile> =
        withDrive(token) { drive -> drive.list().sortedByDescending { it.modifiedMillis } }

    suspend fun download(token: String, file: DriveFile): ByteArray =
        withDrive(token) { drive -> drive.download(file.id) }

    private suspend fun <T> withDrive(token: String, block: suspend (DriveClient) -> T): T = try {
        block(DriveClient(token))
    } catch (e: DriveClient.Error) {
        if (e.code != 401) throw e
        block(DriveClient(DriveAuth.refresh(appContext, token)))
    }
}
