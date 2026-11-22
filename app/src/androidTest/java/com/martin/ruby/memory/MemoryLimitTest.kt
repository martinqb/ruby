package com.martin.ruby.memory

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Runs on a phone or emulator: the storage limit packs and moves memories without ever
 * losing one, and brings them back. Google Drive is replaced by a fake, and the database
 * is its own file, so this is safe to run on a phone with real memory on it.
 */
@RunWith(AndroidJUnit4::class)
class MemoryLimitTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: MemoryDatabase
    private lateinit var archiver: MemoryArchiver
    private val drive = FakeDrive()

    @Before
    fun setUp() {
        context.deleteDatabase(TEST_FILE)
        db = MemoryDatabase.open(context, TEST_FILE)
        archiver = MemoryArchiver(db, context.getDatabasePath(TEST_FILE))
    }

    @After
    fun cleanUp() {
        db.close()
        context.deleteDatabase(TEST_FILE)
    }

    /** A Google Drive in memory. "Encryption" is a marker, enough to check it's used. */
    private class FakeDrive : ArchiveCloud {
        val files = mutableMapOf<String, ByteArray>()
        var corruptDownloads = false
        var uploads = 0
        override suspend fun seal(packed: ByteArray) = SEALED + packed
        override suspend fun open(sealed: ByteArray, secret: String?): ByteArray {
            check(sealed.copyOfRange(0, SEALED.size).contentEquals(SEALED))
            return sealed.copyOfRange(SEALED.size, sealed.size)
        }
        override suspend fun upload(name: String, bytes: ByteArray): String {
            uploads++
            return "file$uploads".also { files[it] = bytes.copyOf() }
        }
        override suspend fun download(fileId: String): ByteArray {
            val bytes = files[fileId]?.copyOf() ?: throw IOException("No such file")
            if (corruptDownloads) bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
            return bytes
        }
        override suspend fun delete(fileId: String) {
            files.remove(fileId)
        }
        companion object {
            val SEALED = "SEALED:".toByteArray()
        }
    }

    private val noDrive: suspend () -> ArchiveCloud = { throw CloudUnavailable("Backup isn't set up.") }

    private suspend fun addNotes(count: Int) {
        db.dao().insertAll(TestNotes.make(count, 2_000_000L, java.util.Random(7)))
    }

    /** Every message, wherever it is now: in memory, compressed on the phone, or in Drive. */
    private suspend fun totalMessages(): Int =
        db.dao().countNow() + db.archiveDao().stored().first().sumOf { it.messageCount }

    @Test
    fun underTheLimit_nothingHappens() = runBlocking {
        addNotes(10)
        val result = archiver.enforce(10_000_000, noDrive)
        assertEquals(0, result.packedMessages)
        assertNull(result.problem)
        assertEquals(10, db.dao().countNow())
    }

    @Test
    fun sizes_forChoosingTheTinyTestLimit() = runBlocking {
        archiver.enforce(Long.MAX_VALUE, noDrive) // creates and checkpoints the file
        val empty = archiver.usageBytes()
        addNotes(50)
        archiver.enforce(Long.MAX_VALUE, noDrive) // checkpoint so the size is accurate
        val fifty = archiver.usageBytes()
        archiver.enforce(1, noDrive)
        val packed = archiver.usageBytes()
        Log.i(TAG, "Empty database: $empty bytes. With 50 notes: $fifty bytes. Packed: $packed bytes.")
        // The tiny limit must leave room for the empty database and some notes.
        assertTrue("Empty database is $empty bytes", empty < StorageBudget.TINY_TEST_BYTES * StorageBudget.TARGET / 2)
        assertTrue("50 notes made only $fifty bytes", fifty - empty > 20_000)
    }

    @Test
    fun atTheLimit_compressesOldest_keepsNewest_losesNothing() = runBlocking {
        addNotes(200)
        archiver.enforce(Long.MAX_VALUE, noDrive)
        val full = archiver.usageBytes()
        val limit = full - 1 // just over

        val result = archiver.enforce(limit, noDrive)
        Log.i(TAG, "Packed ${result.packedMessages}: ${result.beforeBytes} -> ${result.afterBytes} bytes, limit $limit.")
        assertTrue(result.packedMessages > 0)
        assertTrue("Still at ${result.afterBytes} of $limit", result.afterBytes <= limit * StorageBudget.TARGET)
        assertNull(result.problem)
        assertEquals(200, totalMessages())

        // The newest are still in memory, and the oldest went first.
        val inMemory = db.dao().recent(500).first()
        assertTrue(inMemory.any { it.text.startsWith("Test note 200 of 200:") })
        assertTrue(inMemory.none { it.text.startsWith("Test note 1 of 200:") })
        assertTrue(db.archiveDao().stored().first().all { it.state == MemoryArchive.STATE_PHONE })
    }

    @Test
    fun compressionNotEnough_movesOldestToDrive_andBringsThemBack() = runBlocking {
        addNotes(300)
        archiver.enforce(Long.MAX_VALUE, noDrive)
        // Pack everything packable on the phone first.
        archiver.enforce(1, noDrive)
        assertEquals(MemoryArchiver.KEEP_NEWEST, db.dao().countNow())
        val packed = archiver.usageBytes()

        // A limit the compressed bundles don't fit in: the oldest move to Drive.
        val result = archiver.enforce(packed - 1, { drive })
        Log.i(TAG, "Moved ${result.movedToDrive} to Drive: ${result.beforeBytes} -> ${result.afterBytes} bytes.")
        assertTrue(result.movedToDrive > 0)
        assertEquals(result.movedToDrive, drive.files.size)
        assertEquals(300, totalMessages())
        val inDrive = db.archiveDao().stored().first().filter { it.state == MemoryArchive.STATE_DRIVE }
        assertEquals(result.movedToDrive, inDrive.size)
        assertNull("Phone copy kept after the move", db.archiveDao().get(inDrive.first().id)!!.data)

        // Bring the oldest back: it's searchable again, and Drive's copy is gone.
        val oldest = inDrive.first()
        val back = archiver.bringBack(oldest.id, { drive })
        assertEquals(oldest.messageCount, back)
        assertEquals(result.movedToDrive - 1, drive.files.size)
        assertEquals(300, totalMessages())
        val indexed = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages_fts WHERE messages_fts MATCH 'test'").use { it.moveToFirst(); it.getInt(0) }
        assertEquals("Search index covers what came back", db.dao().countNow(), indexed)
        assertTrue(db.dao().recent(1000).first().any { it.text.startsWith("Test note 1 of 300:") })

        // And it isn't packed straight away again.
        val again = archiver.enforce(1, noDrive)
        assertTrue(db.dao().recent(1000).first().any { it.text.startsWith("Test note 1 of 300:") })
        assertNotNull(again.problem) // can't get under 1 byte, and says so
        assertEquals(300, totalMessages())
    }

    @Test
    fun noDrive_keepsEverything_andSaysWhy() = runBlocking {
        addNotes(300)
        archiver.enforce(Long.MAX_VALUE, noDrive)
        val result = archiver.enforce(1, noDrive)
        assertEquals("Backup isn't set up.", result.problem)
        assertEquals(300, totalMessages())
    }

    @Test
    fun badDriveCopy_staysOnThePhone() = runBlocking {
        addNotes(300)
        archiver.enforce(Long.MAX_VALUE, noDrive)
        archiver.enforce(1, noDrive)
        drive.corruptDownloads = true
        val result = archiver.enforce(1, { drive })
        assertEquals(0, result.movedToDrive)
        assertTrue(result.problem!!.contains("didn't match"))
        assertTrue(drive.files.isEmpty()) // the bad upload was removed
        assertTrue(db.archiveDao().stored().first().all { it.state == MemoryArchive.STATE_PHONE })
        assertEquals(300, totalMessages())
    }


    @Test
    fun moveOldestToDrive_testButton() = runBlocking {
        addNotes(100)
        assertEquals(false, archiver.moveOldestToDrive { drive }) // nothing compressed yet
        archiver.enforce(Long.MAX_VALUE, noDrive)
        archiver.enforce(1, noDrive)
        val bundles = db.archiveDao().stored().first().size
        assertTrue(archiver.moveOldestToDrive { drive })
        assertEquals(1, drive.files.size)
        assertEquals(1, db.archiveDao().stored().first().count { it.state == MemoryArchive.STATE_DRIVE })
        assertEquals(bundles, db.archiveDao().stored().first().size)
        assertEquals(100, totalMessages())
    }
    @Test
    fun bringBack_fromThePhone() = runBlocking {
        addNotes(100)
        archiver.enforce(Long.MAX_VALUE, noDrive)
        archiver.enforce(1, noDrive)
        val bundle = db.archiveDao().stored().first().first()
        archiver.bringBack(bundle.id, noDrive)
        assertEquals(100, totalMessages())
        assertTrue(db.archiveDao().stored().first().none { it.id == bundle.id })
    }

    @Test
    fun upgradeFromVersion1_keepsMessagesAndSearch() = runBlocking {
        db.close()
        context.deleteDatabase(TEST_FILE)
        createVersion1(listOf("Parked on level 3, row C", "Tyre pressure is 36 psi"))

        db = MemoryDatabase.open(context, TEST_FILE)
        assertEquals(2, db.dao().countNow())
        val found = db.dao().search(MemoryDatabase.searchQuery(MemorySearch.ftsQuery("parked")!!)).first()
        assertEquals(1, found.size)
        // The new table works.
        archiver = MemoryArchiver(db, context.getDatabasePath(TEST_FILE))
        assertEquals(emptyList<ArchiveSummary>(), db.archiveDao().stored().first())
    }

    /** Writes a database exactly as the version on phones before this change made it. */
    private fun createVersion1(texts: List<String>) {
        System.loadLibrary("sqlcipher")
        val hex = MemoryKey.get(context).joinToString("") { "%02x".format(it) }
        val helper = SupportOpenHelperFactory("x'$hex'".toByteArray()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_FILE)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timeMillis` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `context` TEXT)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_timeMillis` ON `messages` (`timeMillis`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4cf32c699b6da3d6b4f7ee6696e07f42')")
                        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(text, content='messages', content_rowid='id', tokenize='porter unicode61')")
                        db.execSQL("CREATE TRIGGER IF NOT EXISTS messages_ai AFTER INSERT ON messages BEGIN INSERT INTO messages_fts(rowid, text) VALUES (new.id, new.text); END")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        texts.forEachIndexed { i, text ->
            helper.writableDatabase.execSQL(
                "INSERT INTO messages (timeMillis, role, text) VALUES (?, 'user', ?)", arrayOf<Any>(i.toLong(), text)
            )
        }
        helper.close()
    }

    private companion object {
        const val TEST_FILE = "memory-limit-test.db"
        const val TAG = "MemoryLimitTest"
    }
}
