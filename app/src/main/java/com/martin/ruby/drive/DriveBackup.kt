package com.martin.ruby.drive

import android.content.Context
import com.martin.ruby.backup.BackupManager
import com.martin.ruby.backup.BackupStatusStore
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

    /**
     * Makes a backup, uploads it, then deletes all but the newest [DriveRetention.KEEP],
     * and records the success for the Backup screen. Upload and clean-up are retried
     * separately, so a token refresh after the upload can't upload it twice.
     */
    suspend fun backUp(token: String): Uploaded {
        val backup = manager.makeBackup()
        val name = backupFileName()
        val file = withDrive(token) { drive -> drive.upload(name, backup.bytes) }
        // Only after the new one is safely up. A failed delete isn't worth failing over.
        val deleted = runCatching {
            withDrive(token) { drive ->
                DriveRetention.toDelete(drive.list()).count { runCatching { drive.delete(it.id) }.isSuccess }
            }
        }.getOrDefault(0)
        BackupStatusStore(appContext).recordSuccess(backup.messageCount, file.sizeBytes)
        SensingLog.add(
            "Backed up to Google Drive: ${backup.messageCount} messages, ${backup.bytes.size / 1024} KB" +
                (if (deleted > 0) ", removed $deleted older." else ".")
        )
        return Uploaded(file, backup.messageCount, deleted)
    }

    /** The backups in Drive, newest first. Memories moved there by the storage limit aren't listed. */
    suspend fun list(token: String): List<DriveFile> =
        withDrive(token) { drive -> drive.list().filter { it.isBackup }.sortedByDescending { it.modifiedMillis } }

    suspend fun download(token: String, file: DriveFile): ByteArray =
        withDrive(token) { drive -> drive.download(file.id) }

    // Any file, for memories the storage limit moves to Drive

    suspend fun uploadFile(token: String, name: String, bytes: ByteArray): DriveFile =
        withDrive(token) { drive -> drive.upload(name, bytes) }

    suspend fun downloadFile(token: String, id: String): ByteArray =
        withDrive(token) { drive -> drive.download(id) }

    suspend fun deleteFile(token: String, id: String) =
        withDrive(token) { drive -> drive.delete(id) }

    companion object {
        /** Names of files holding memories the storage limit moved to Drive. */
        const val ARCHIVE_PREFIX = "ruby-archive-"
    }

    private suspend fun <T> withDrive(token: String, block: suspend (DriveClient) -> T): T = try {
        block(DriveClient(token))
    } catch (e: DriveClient.Error) {
        if (e.code != 401) throw e
        block(DriveClient(DriveAuth.refresh(appContext, token)))
    }
}
