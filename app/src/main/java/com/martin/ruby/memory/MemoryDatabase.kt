package com.martin.ruby.memory

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.martin.ruby.reminders.Reminder
import com.martin.ruby.reminders.ReminderDao
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/** One message in Ruby's memory. */
@Entity(tableName = "messages", indices = [Index("timeMillis")])
data class MemoryMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** When it was written (wall-clock time). */
    val timeMillis: Long,
    /** Who wrote it: [ROLE_USER] or [ROLE_RUBY]. */
    val role: String,
    val text: String,
    /** What the decision engine said you were doing at the time, e.g. "Driving". */
    val context: String? = null,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_RUBY = "ruby"
    }
}

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(message: MemoryMessage): Long

    /** Newest first. */
    @Query("SELECT * FROM messages ORDER BY timeMillis DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<MemoryMessage>>

    @Query("SELECT COUNT(*) FROM messages")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countNow(): Int

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(messages: List<MemoryMessage>): List<Long>

    /** The notes made by [MemoryStore.addTestNotes]. */
    @Query("DELETE FROM messages WHERE context = 'Test' AND text LIKE 'Test note %'")
    suspend fun deleteTestNotes(): Int

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    /** For export: the next [limit] messages after ([afterTime], [afterId]), oldest first. */
    @Query(
        "SELECT * FROM messages WHERE timeMillis > :afterTime OR (timeMillis = :afterTime AND id > :afterId) " +
            "ORDER BY timeMillis, id LIMIT :limit"
    )
    suspend fun pageAfter(afterTime: Long, afterId: Long, limit: Int): List<MemoryMessage>

    /**
     * The oldest messages that may be packed away: never the newest [keepNewest], and not
     * ones the user just brought back.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE id NOT IN (SELECT id FROM messages ORDER BY timeMillis DESC, id DESC LIMIT :keepNewest)
        AND NOT EXISTS (
            SELECT 1 FROM memory_archive a WHERE a.state = 'back'
            AND messages.id BETWEEN a.backFirstId AND a.backLastId AND a.heldUntilMillis > :now
        )
        ORDER BY timeMillis, id
        LIMIT :limit
        """
    )
    suspend fun packable(keepNewest: Int, now: Long, limit: Int): List<MemoryMessage>

    /** Full-text search; build the query with [MemoryDatabase.searchQuery]. */
    @RawQuery(observedEntities = [MemoryMessage::class])
    fun search(query: SupportSQLiteQuery): Flow<List<MemoryMessage>>

    /** A one-off full-text search; build the query with [MemoryDatabase.recallQuery]. */
    @RawQuery
    suspend fun searchNow(query: SupportSQLiteQuery): List<MemoryMessage>
}

/**
 * Ruby's memory: an SQLite database encrypted with SQLCipher. Without [MemoryKey] the
 * file is unreadable noise.
 *
 * Search uses SQLite's full-text search (FTS5). The `messages_fts` table is an index of
 * the message text, kept up to date by triggers. Room doesn't know about FTS5 tables,
 * so they're made with plain SQL when the database opens.
 */
@Database(
    entities = [MemoryMessage::class, MemoryArchive::class, Reminder::class],
    version = 3,
    exportSchema = true,
    // Version 2 added memory_archive. Room works out the change from the saved schemas.
    // Version 3 added reminders.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun dao(): MemoryDao

    abstract fun archiveDao(): ArchiveDao

    abstract fun reminderDao(): ReminderDao

    companion object {
        const val FILE_NAME = "memory.db"

        @Volatile private var instance: MemoryDatabase? = null

        /** The one open database. Call off the main thread (it reads the Keystore). */
        fun get(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: open(context.applicationContext).also { instance = it }
            }

        /** Closes the database, e.g. before a restore replaces the file. */
        fun close() = synchronized(this) {
            instance?.close()
            instance = null
        }

        /** A consistent copy of the database files, for a backup. */
        class Snapshot(val database: ByteArray, val wal: ByteArray, val messageCount: Int)

        /**
         * Copies the database files while holding the write lock, so nothing can change them
         * halfway through the copy. The files stay encrypted with the database key.
         */
        suspend fun snapshot(context: Context): Snapshot = withContext(Dispatchers.IO) {
            val db = get(context)
            // Move recent changes into the main file first, so the log is small.
            runCatching {
                db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            }
            db.withTransaction {
                val file = context.getDatabasePath(FILE_NAME)
                Snapshot(
                    database = file.readBytes(),
                    wal = File(file.path + "-wal").takeIf { it.exists() }?.readBytes() ?: ByteArray(0),
                    messageCount = db.dao().countNow(),
                )
            }
        }

        /**
         * Replaces memory with restored files and their key. The current files and key are
         * kept aside until the restored database has opened and been read; if anything
         * fails, they're put back and the error is passed on. Returns the message count.
         */
        suspend fun replace(context: Context, database: ByteArray, wal: ByteArray, key: ByteArray): Int =
            withContext(Dispatchers.IO) {
                synchronized(this@Companion) {
                    close()
                    val main = context.getDatabasePath(FILE_NAME)
                    main.parentFile?.mkdirs()
                    val parts = listOf("", "-wal", "-shm", "-journal").map { File(main.path + it) }
                    val asides = parts.map { File(it.path + ".before-restore") }
                    val oldKey = runCatching { MemoryKey.get(context) }.getOrNull()

                    asides.forEach { it.delete() }
                    parts.zip(asides).forEach { (part, aside) -> if (part.exists()) part.renameTo(aside) }
                    try {
                        main.writeBytes(database)
                        if (wal.isNotEmpty()) File(main.path + "-wal").writeBytes(wal)
                        MemoryKey.save(context, key)
                        val restored = open(context)
                        val count = restored.openHelper.writableDatabase
                            .query("SELECT COUNT(*) FROM messages")
                            .use { it.moveToFirst(); it.getInt(0) }
                        instance = restored
                        asides.forEach { it.delete() }
                        count
                    } catch (e: Exception) {
                        close()
                        parts.forEach { it.delete() }
                        parts.zip(asides).forEach { (part, aside) -> if (aside.exists()) aside.renameTo(part) }
                        oldKey?.let { MemoryKey.save(context, it) }
                        throw e
                    }
                }
            }

        private fun open(context: Context): MemoryDatabase = open(context, FILE_NAME)

        /**
         * Opens a database file directly, not shared. The app uses [get]; tests use this
         * with their own file name so they never touch real memory.
         */
        fun open(context: Context, fileName: String): MemoryDatabase {
            System.loadLibrary("sqlcipher")
            // A raw key (x'hex') skips SQLCipher's slow passphrase step: the key is
            // already 32 random bytes, so there's nothing to strengthen.
            val hex = MemoryKey.get(context).joinToString("") { "%02x".format(it) }
            val factory = SupportOpenHelperFactory("x'$hex'".toByteArray())
            return Room.databaseBuilder(context, MemoryDatabase::class.java, fileName)
                .openHelperFactory(factory)
                .addCallback(FullTextSearch)
                .build()
        }

        /** Builds a search for [MemorySearch.ftsQuery] output, best matches first. */
        fun searchQuery(fts: String, limit: Int = 100): SupportSQLiteQuery = SimpleSQLiteQuery(
            """
            SELECT messages.* FROM messages
            JOIN messages_fts ON messages.id = messages_fts.rowid
            WHERE messages_fts MATCH ?
            ORDER BY bm25(messages_fts)
            LIMIT $limit
            """.trimIndent(),
            arrayOf(fts),
        )

        /**
         * For chat recall: the best [limit] matches for [MemoryRecall.ftsQuery] output,
         * leaving out the storage limit's test notes.
         */
        fun recallQuery(fts: String, limit: Int): SupportSQLiteQuery = SimpleSQLiteQuery(
            """
            SELECT messages.* FROM messages
            JOIN messages_fts ON messages.id = messages_fts.rowid
            WHERE messages_fts MATCH ?
            AND NOT (IFNULL(messages.context, '') = 'Test' AND messages.text LIKE 'Test note %')
            ORDER BY bm25(messages_fts)
            LIMIT $limit
            """.trimIndent(),
            arrayOf(fts),
        )
    }

    /** Makes the FTS5 index and the triggers that keep it in step with `messages`. */
    private object FullTextSearch : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            // "IF NOT EXISTS" so this is safe on every open, including after a restore.
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(" +
                    "text, content='messages', content_rowid='id', tokenize='porter unicode61')"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN " +
                    "INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS messages_ad AFTER DELETE ON messages BEGIN " +
                    "INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS messages_au AFTER UPDATE ON messages BEGIN " +
                    "INSERT INTO messages_fts(messages_fts, rowid, text) VALUES ('delete', old.id, old.text); " +
                    "INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text); END"
            )
        }
    }
}
