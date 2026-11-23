package com.martin.ruby.drive

import android.content.Context
import com.martin.ruby.backup.BackupManager
import com.martin.ruby.memory.ArchiveCloud
import com.martin.ruby.memory.CloudUnavailable

/**
 * Moves memories to the user's Google Drive for the storage limit: encrypted with the
 * backup key (so the passphrase or recovery key opens them on a new phone), into the
 * same private app folder as backups.
 */
class DriveArchiveCloud private constructor(
    private val manager: BackupManager,
    private val drive: DriveBackup,
    private val token: String,
) : ArchiveCloud {

    override suspend fun seal(packed: ByteArray) = manager.seal(packed)
    override suspend fun open(sealed: ByteArray, secret: String?) = manager.openSealed(sealed, secret)
    override suspend fun upload(name: String, bytes: ByteArray) = drive.uploadFile(token, name, bytes).id
    override suspend fun download(fileId: String) = drive.downloadFile(token, fileId)
    override suspend fun delete(fileId: String) = drive.deleteFile(token, fileId)

    companion object {
        /**
         * Gets Drive ready without asking the user anything; throws [CloudUnavailable]
         * with a plain-English reason if it can't be.
         */
        suspend fun connect(context: Context): ArchiveCloud {
            val manager = BackupManager(context)
            if (!manager.isSetUp()) {
                throw CloudUnavailable(
                    "Backup isn't set up, so older memories can't be moved to Google Drive. " +
                        "Set it up on the Backup screen."
                )
            }
            val token = try {
                DriveAuth.silentToken(context)
            } catch (e: DriveAuth.NeedsApproval) {
                throw CloudUnavailable("Google Drive access needs approving again on the Backup screen.")
            } catch (e: DriveAuth.Failed) {
                throw CloudUnavailable("Couldn't reach Google Drive: ${e.message}", retryLater = true)
            }
            return DriveArchiveCloud(manager, DriveBackup(context, manager), token)
        }
    }
}
