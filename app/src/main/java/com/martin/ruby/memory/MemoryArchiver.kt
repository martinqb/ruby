package com.martin.ruby.memory

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where memories go when they're moved off the phone: encrypted, into the user's Google
 * Drive. The app uses [com.martin.ruby.drive.DriveArchiveCloud]; tests use a fake.
 */
interface ArchiveCloud {
    /** Encrypts with the backup key, so the passphrase or recovery key can open it anywhere. */
    suspend fun seal(packed: ByteArray): ByteArray
    /** Opens what [seal] made; with [secret] (an earlier passphrase or recovery key) if given. */
    suspend fun open(sealed: ByteArray, secret: String?): ByteArray
    /** Uploads and returns the file's id. */
    suspend fun upload(name: String, bytes: ByteArray): String
    suspend fun download(fileId: String): ByteArray
    suspend fun delete(fileId: String)
}

/** Why memories couldn't be moved to Drive; shown to the user as is. */
class CloudUnavailable(message: String, val retryLater: Boolean = false) : Exception(message)

/**
 * Keeps memory under its storage limit without ever deleting a memory:
 *  1. compacts the database (gives back space left over from earlier changes);
 *  2. packs the oldest messages into compressed bundles kept on the phone;
 *  3. if that's not enough, moves the oldest bundles to Google Drive, encrypted, and only
 *     forgets the phone's copy once Drive's copy has been downloaded again and checked.
 * The user can bring any bundle back with [bringBack].
 *
 * Works on any [MemoryDatabase], so the emulator test runs it on its own file.
 */
class MemoryArchiver(private val db: MemoryDatabase, private val dbFile: File) {

    class Result(
        val beforeBytes: Long,
        val afterBytes: Long,
        val packedMessages: Int,
        val movedToDrive: Int,
        /** Why it couldn't get back under the limit, or null if it did (or didn't need to). */
        val problem: String?,
        /** The problem may go away by itself (no network, say), so it's worth trying again later. */
        val retryLater: Boolean = false,
    )

    /** Memory's size on disk: the database file and its write-ahead log. */
    fun usageBytes(): Long = dbFile.length() + File(dbFile.path + "-wal").length()

    /**
     * Gets usage under [limitBytes] (down to [StorageBudget.TARGET] of it), if it's at or
     * over. [cloud] is asked for only if bundles need moving to Drive; it may throw
     * [CloudUnavailable] or a Drive error, which becomes [Result.problem].
     */
    suspend fun enforce(limitBytes: Long, cloud: suspend () -> ArchiveCloud): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            db.archiveDao().dropExpiredMarkers(System.currentTimeMillis())
            checkpoint()
            val before = usageBytes()
            if (before < limitBytes) return@withContext Result(before, before, 0, 0, null)
            val target = (limitBytes * StorageBudget.TARGET).toLong()

            // 1. Compact.
            compact()
            var used = usageBytes()

            // 2. Pack the oldest messages, a few rounds at most.
            var packed = 0
            var rounds = 0
            while (used > target && rounds < MAX_ROUNDS) {
                val count = db.dao().countNow()
                if (count <= KEEP_NEWEST) break
                // A rough guess at how many to pack: each message frees roughly its share of
                // the file, less what its compressed copy takes.
                val perMessage = maxOf(1L, used / count)
                val wanted = ((used - target) / (perMessage * 6 / 10).coerceAtLeast(1)).toInt().coerceAtLeast(MIN_PACK)
                var packedThisRound = 0
                while (packedThisRound < wanted) {
                    val n = packOldest(minOf(BUNDLE_SIZE, wanted - packedThisRound))
                    if (n == 0) break
                    packedThisRound += n
                }
                if (packedThisRound == 0) break
                packed += packedThisRound
                compact()
                used = usageBytes()
                rounds++
            }

            // 3. Move the oldest bundles to Drive.
            var moved = 0
            var problem: String? = null
            var retryLater = false
            if (used > target && db.archiveDao().oldestOnPhone() != null) {
                try {
                    val drive = cloud()
                    while (used > target) {
                        val id = db.archiveDao().oldestOnPhone() ?: break
                        moveToDrive(id, drive)
                        moved++
                        compact()
                        used = usageBytes()
                    }
                } catch (e: CloudUnavailable) {
                    problem = e.message
                    retryLater = e.retryLater
                } catch (e: IOException) {
                    problem = "Couldn't move older memories to Google Drive: ${e.message}"
                    retryLater = true
                }
            }
            if (problem == null && used >= limitBytes) {
                problem = "Memory is still over its limit: the newest $KEEP_NEWEST messages and " +
                    "the ones you brought back stay on the phone."
            }
            Result(before, used, packed, moved, problem, retryLater)
        }
    }

    /**
     * For testing: moves the oldest compressed bundle on the phone to Drive now, whatever
     * the limit, the same way [enforce] does. Returns false if there's none on the phone.
     */
    suspend fun moveOldestToDrive(cloud: suspend () -> ArchiveCloud): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val id = db.archiveDao().oldestOnPhone() ?: return@withContext false
            moveToDrive(id, cloud())
            compact()
            true
        }
    }

    /** Packs up to [max] of the oldest messages into one bundle. Returns how many. */
    private suspend fun packOldest(max: Int): Int = db.withTransaction {
        val messages = db.dao().packable(KEEP_NEWEST, System.currentTimeMillis(), max)
        if (messages.isEmpty()) return@withTransaction 0
        val packed = MemoryPack.pack(messages)
        // Check the bundle opens before the originals go.
        check(MemoryPack.unpack(packed).size == messages.size)
        db.archiveDao().insert(
            MemoryArchive(
                state = MemoryArchive.STATE_PHONE,
                firstMillis = messages.first().timeMillis,
                lastMillis = messages.last().timeMillis,
                messageCount = messages.size,
                data = packed,
                sizeBytes = packed.size.toLong(),
                createdMillis = System.currentTimeMillis(),
            )
        )
        messages.chunked(500).forEach { batch -> db.dao().deleteIds(batch.map { it.id }) }
        messages.size
    }

    private suspend fun moveToDrive(id: Long, drive: ArchiveCloud) {
        val archive = db.archiveDao().get(id) ?: return
        val data = archive.data ?: return
        val sealed = drive.seal(data)
        if (!drive.open(sealed, null).contentEquals(data)) throw IOException("Encryption check failed.")
        val fileId = drive.upload(archiveName(archive), sealed)
        // Only forget the phone's copy once Drive's copy is known to be good.
        val sha = sha256(sealed)
        val check = runCatching { drive.download(fileId) }.getOrNull()
        if (check == null || sha256(check) != sha) {
            runCatching { drive.delete(fileId) }
            throw IOException("Google Drive's copy didn't match, so it was kept on the phone.")
        }
        db.archiveDao().movedToDrive(id, fileId, sha)
    }

    /** Thrown by [bringBack] when a bundle was locked under an earlier passphrase. */
    class NeedsOldSecret : Exception(
        "These memories were moved to Drive under an earlier passphrase. Enter that passphrase or its recovery key."
    )

    /**
     * Puts a bundle's messages back into memory, searchable again, and keeps them on the
     * phone for [HOLD_MILLIS] so they aren't packed away straight after. Returns the
     * number of messages. Only after they're safely back is Drive's copy removed.
     */
    suspend fun bringBack(id: Long, cloud: suspend () -> ArchiveCloud, secret: String? = null): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val archive = db.archiveDao().get(id) ?: throw IOException("That archive no longer exists.")
            var drive: ArchiveCloud? = null
            val packed = when (archive.state) {
                MemoryArchive.STATE_PHONE -> archive.data ?: throw IOException("This archive is empty.")
                MemoryArchive.STATE_DRIVE -> {
                    val fileId = archive.driveFileId ?: throw IOException("This archive has no Drive file.")
                    drive = cloud()
                    val sealed = drive.download(fileId)
                    if (archive.driveSha256 != null && sha256(sealed) != archive.driveSha256) {
                        throw IOException("Google Drive's copy is damaged.")
                    }
                    try {
                        drive.open(sealed, secret)
                    } catch (e: com.martin.ruby.backup.BackupCrypto.CantOpen) {
                        if (secret == null) throw NeedsOldSecret() else throw e
                    }
                }
                else -> throw IOException("These memories are already back.")
            }
            val messages = MemoryPack.unpack(packed)
            if (messages.size != archive.messageCount) throw IOException("This archive is incomplete.")
            db.withTransaction {
                val ids = db.dao().insertAll(messages)
                db.archiveDao().broughtBack(
                    id, ids.minOrNull() ?: 0, ids.maxOrNull() ?: 0, System.currentTimeMillis() + HOLD_MILLIS
                )
            }
            // The messages are back in memory, so Drive's copy is no longer needed. If this
            // fails, the next check tries again ([cleanUpDrive]).
            if (drive != null && archive.driveFileId != null) {
                runCatching {
                    drive.delete(archive.driveFileId)
                    db.archiveDao().forgetDriveFile(id)
                }
            }
            messages.size
        }
    }

    /** Removes Drive copies of bundles that were brought back but couldn't be removed then. */
    suspend fun cleanUpDrive(cloud: suspend () -> ArchiveCloud) = lock.withLock {
        withContext(Dispatchers.IO) {
            val leftovers = db.archiveDao().backWithDriveFile()
            if (leftovers.isEmpty()) return@withContext
            val drive = runCatching { cloud() }.getOrNull() ?: return@withContext
            for (archive in leftovers) runCatching {
                drive.delete(archive.driveFileId!!)
                db.archiveDao().forgetDriveFile(archive.id)
            }
        }
    }

    /** Moves the log into the main file, so the size on disk is accurate. */
    private fun checkpoint() {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
    }

    /** Tidies the search index and rewrites the file without its empty space. */
    private fun compact() {
        val sql = db.openHelper.writableDatabase
        runCatching { sql.execSQL("INSERT INTO messages_fts(messages_fts) VALUES('optimize')") }
        sql.execSQL("VACUUM")
        checkpoint()
    }

    companion object {
        /** The newest messages always stay unpacked. */
        const val KEEP_NEWEST = 20
        /** Brought-back messages stay on the phone at least this long. */
        const val HOLD_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val BUNDLE_SIZE = 500
        private const val MIN_PACK = 20
        private const val MAX_ROUNDS = 6

        /** One at a time across the app, so a check and a bring-back never overlap. */
        private val lock = Mutex()

        /**
         * Runs [block] while nothing can pack, move or bring back memories, so it sees every
         * message exactly once (export uses this). Don't call the archiver from inside it.
         */
        suspend fun <T> whileArchivesStill(block: suspend () -> T): T = lock.withLock { block() }

        fun archiveName(archive: MemoryArchive) =
            "${com.martin.ruby.drive.DriveBackup.ARCHIVE_PREFIX}${archive.createdMillis}-${archive.id}.rubyarchive"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
