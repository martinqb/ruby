package com.martin.ruby.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * What's inside a backup, before encryption: the memory database files and the key that
 * opens them, compressed with gzip. Compressing happens before encrypting, because
 * encrypted data looks random and can't be compressed.
 */
class BackupContents(
    /** The memory database key, so a new phone can open the restored files. */
    val databaseKey: ByteArray,
    /** When the backup was made (wall-clock time). */
    val createdMillis: Long,
    /** How many messages were in memory, to show before restoring. */
    val messageCount: Int,
    /** The database file. */
    val database: ByteArray,
    /** Its write-ahead log: recent changes not yet moved into [database]. May be empty. */
    val wal: ByteArray,
) {

    fun pack(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(FORMAT)
            out.writeLong(createdMillis)
            out.writeInt(messageCount)
            out.writeBlock(databaseKey)
            out.writeBlock(database)
            out.writeBlock(wal)
        }
    }.toByteArray()

    companion object {
        private const val FORMAT = 1
        private const val MAX_BLOCK = 1 shl 30 // 1 GB; anything larger means a damaged file

        fun unpack(packed: ByteArray): BackupContents = try {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(packed))).use { input ->
                val format = input.readInt()
                if (format != FORMAT) throw IOException("Unknown backup contents format $format.")
                BackupContents(
                    createdMillis = input.readLong(),
                    messageCount = input.readInt(),
                    databaseKey = input.readBlock(),
                    database = input.readBlock(),
                    wal = input.readBlock(),
                )
            }
        } catch (e: IOException) {
            throw BackupCrypto.CantOpen("The backup's contents are damaged: ${e.message}")
        }

        private fun DataOutputStream.writeBlock(bytes: ByteArray) {
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataInputStream.readBlock(): ByteArray {
            val size = readInt()
            if (size !in 0..MAX_BLOCK) throw IOException("Bad block size $size.")
            return ByteArray(size).also { readFully(it) }
        }
    }
}
