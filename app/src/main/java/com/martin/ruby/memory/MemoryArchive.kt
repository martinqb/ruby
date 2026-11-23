package com.martin.ruby.memory

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A bundle of older messages, packed together and compressed to save space when memory
 * reaches its storage limit. Nothing in it is ever thrown away: it's either
 *  - [STATE_PHONE]: compressed, kept on this phone in [data];
 *  - [STATE_DRIVE]: encrypted and moved to the user's Google Drive ([driveFileId]);
 *  - [STATE_BACK]: brought back into memory by the user. Only a marker is left, so the
 *    same messages aren't packed away again straight after ([heldUntilMillis]).
 */
@Entity(tableName = "memory_archive")
class MemoryArchive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val state: String,
    /** Time of the oldest and newest message inside. */
    val firstMillis: Long,
    val lastMillis: Long,
    val messageCount: Int,
    /** The compressed messages ([MemoryPack]), while [state] is [STATE_PHONE]. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val data: ByteArray? = null,
    /** Size of the compressed (or, in Drive, encrypted) bundle. */
    val sizeBytes: Long,
    val driveFileId: String? = null,
    /** SHA-256 of the file in Drive, checked when it comes back. */
    val driveSha256: String? = null,
    val createdMillis: Long,
    /** For [STATE_BACK]: the ids the messages got when they came back, and how long they stay. */
    val backFirstId: Long? = null,
    val backLastId: Long? = null,
    val heldUntilMillis: Long? = null,
) {
    companion object {
        const val STATE_PHONE = "phone"
        const val STATE_DRIVE = "drive"
        const val STATE_BACK = "back"
    }
}

/** An archive without its data, for lists. */
data class ArchiveSummary(
    val id: Long,
    val state: String,
    val firstMillis: Long,
    val lastMillis: Long,
    val messageCount: Int,
    val sizeBytes: Long,
    val heldUntilMillis: Long?,
)

@Dao
interface ArchiveDao {

    @Insert
    suspend fun insert(archive: MemoryArchive): Long

    @Query("SELECT * FROM memory_archive WHERE id = :id")
    suspend fun get(id: Long): MemoryArchive?

    /** The packed-away bundles (not the "brought back" markers), oldest first. */
    @Query(
        "SELECT id, state, firstMillis, lastMillis, messageCount, sizeBytes, heldUntilMillis " +
            "FROM memory_archive WHERE state != 'back' ORDER BY firstMillis"
    )
    fun stored(): Flow<List<ArchiveSummary>>

    /** Like [stored], once, for export. */
    @Query(
        "SELECT id, state, firstMillis, lastMillis, messageCount, sizeBytes, heldUntilMillis " +
            "FROM memory_archive WHERE state != 'back' ORDER BY firstMillis, id"
    )
    suspend fun storedNow(): List<ArchiveSummary>

    @Query("SELECT id FROM memory_archive WHERE state = 'phone' ORDER BY firstMillis LIMIT 1")
    suspend fun oldestOnPhone(): Long?

    @Query("UPDATE memory_archive SET state = 'drive', data = NULL, driveFileId = :fileId, driveSha256 = :sha WHERE id = :id")
    suspend fun movedToDrive(id: Long, fileId: String, sha: String)

    @Query(
        "UPDATE memory_archive SET state = 'back', data = NULL, backFirstId = :firstId, backLastId = :lastId, " +
            "heldUntilMillis = :until WHERE id = :id"
    )
    suspend fun broughtBack(id: Long, firstId: Long, lastId: Long, until: Long)

    @Query("UPDATE memory_archive SET driveFileId = NULL WHERE id = :id")
    suspend fun forgetDriveFile(id: Long)

    /** "Brought back" markers whose Drive copy still needs deleting. */
    @Query("SELECT * FROM memory_archive WHERE state = 'back' AND driveFileId IS NOT NULL")
    suspend fun backWithDriveFile(): List<MemoryArchive>

    /** Markers that have done their job. They hold no messages, so removing them loses nothing. */
    @Query("DELETE FROM memory_archive WHERE state = 'back' AND driveFileId IS NULL AND heldUntilMillis < :now")
    suspend fun dropExpiredMarkers(now: Long)

    /** For "Delete everything": the bundles kept on the phone, plus the markers. Drive ones stay. */
    @Query("DELETE FROM memory_archive WHERE state != 'drive'")
    suspend fun deleteAllOnPhone()

    @Query("SELECT COUNT(*) FROM memory_archive WHERE state = 'drive'")
    suspend fun countInDrive(): Int
}

/**
 * Packs messages into one compressed block and back. Plain Java, so it's unit tested.
 * Message text compresses well (often to a quarter of its size or less).
 */
object MemoryPack {

    private const val MAGIC = 0x52_4D_50_31 // "RMP1"
    private const val MAX_FIELD = 64 shl 20 // 64 MB; more means a damaged block

    fun pack(messages: List<MemoryMessage>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(messages.size)
            for (message in messages) {
                out.writeLong(message.timeMillis)
                out.writeText(message.role)
                out.writeText(message.text)
                out.writeBoolean(message.context != null)
                message.context?.let { out.writeText(it) }
            }
        }
    }.toByteArray()

    /** The messages, without ids: they get new ones when they're put back. */
    fun unpack(packed: ByteArray): List<MemoryMessage> =
        DataInputStream(GZIPInputStream(ByteArrayInputStream(packed))).use { input ->
            if (input.readInt() != MAGIC) throw IOException("This isn't a packed memory block.")
            val count = input.readInt()
            if (count < 0) throw IOException("Damaged memory block.")
            val messages = List(count) {
                MemoryMessage(
                    timeMillis = input.readLong(),
                    role = input.readText(),
                    text = input.readText(),
                    context = if (input.readBoolean()) input.readText() else null,
                )
            }
            // Reading to the end makes gzip check its checksum, so damage is never missed.
            if (input.read() != -1) throw IOException("Damaged memory block.")
            messages
        }

    private fun DataOutputStream.writeText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readText(): String {
        val size = readInt()
        if (size !in 0..MAX_FIELD) throw IOException("Damaged memory block.")
        return ByteArray(size).also { readFully(it) }.toString(Charsets.UTF_8)
    }
}

/** Made-up notes for testing the storage limit. About as compressible as real writing. */
object TestNotes {

    const val CONTEXT = "Test"

    /** [count] notes ending at [nowMillis], oldest first. */
    fun make(count: Int, nowMillis: Long, random: java.util.Random = java.util.Random()): List<MemoryMessage> =
        List(count) { i ->
            val words = List(100) { word(random) }.joinToString(" ")
            MemoryMessage(
                timeMillis = nowMillis - (count - i),
                role = MemoryMessage.ROLE_USER,
                text = "Test note ${i + 1} of $count: $words.",
                context = CONTEXT,
            )
        }

    /** A made-up word, 2 to 8 letters, from common letters. */
    private fun word(random: java.util.Random): String {
        val letters = "etaoinshrdlcumwfgypbvk"
        return String(CharArray(2 + random.nextInt(7)) { letters[minOf(random.nextInt(letters.length), random.nextInt(letters.length))] })
    }
}
