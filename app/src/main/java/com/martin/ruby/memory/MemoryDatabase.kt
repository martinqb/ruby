package com.martin.ruby.memory

import android.content.Context
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
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

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

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** Full-text search; build the query with [MemoryDatabase.searchQuery]. */
    @RawQuery(observedEntities = [MemoryMessage::class])
    fun search(query: SupportSQLiteQuery): Flow<List<MemoryMessage>>
}

/**
 * Ruby's memory: an SQLite database encrypted with SQLCipher. Without [MemoryKey] the
 * file is unreadable noise.
 *
 * Search uses SQLite's full-text search (FTS5). The `messages_fts` table is an index of
 * the message text, kept up to date by triggers. Room doesn't know about FTS5 tables,
 * so they're made with plain SQL when the database opens.
 */
@Database(entities = [MemoryMessage::class], version = 1, exportSchema = true)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun dao(): MemoryDao

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
