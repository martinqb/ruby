package com.martin.ruby.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.backup.BackupCrypto
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.io.StringWriter

/**
 * Runs on a phone or emulator: export writes every message exactly once, oldest first,
 * from memory, from compressed bundles and from Drive, and changes nothing. Uses its own
 * database file and a fake Drive, so it's safe on a phone with real memory on it.
 */
@RunWith(AndroidJUnit4::class)
class MemoryExportDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: MemoryDatabase
    private lateinit var archiver: MemoryArchiver
    private val drive = FakeDrive()
    private lateinit var originals: List<MemoryMessage>

    @Before
    fun setUp() = runBlocking {
        context.deleteDatabase(TEST_FILE)
        db = MemoryDatabase.open(context, TEST_FILE)
        archiver = MemoryArchiver(db, context.getDatabasePath(TEST_FILE))
        originals = TestNotes.make(200, 5_000_000L, java.util.Random(3))
        db.dao().insertAll(originals)
        // Pack all but the newest into bundles, then move the oldest bundle to Drive.
        archiver.enforce(1) { throw CloudUnavailable("not now") }
        archiver.moveOldestToDrive { drive }
        Unit
    }

    @After
    fun cleanUp() {
        db.close()
        context.deleteDatabase(TEST_FILE)
    }

    private class FakeDrive : ArchiveCloud {
        val files = mutableMapOf<String, ByteArray>()
        var lockedWithOldPassphrase = false
        override suspend fun seal(packed: ByteArray) = SEALED + packed
        override suspend fun open(sealed: ByteArray, secret: String?): ByteArray {
            if (lockedWithOldPassphrase) throw BackupCrypto.CantOpen("wrong key")
            return sealed.copyOfRange(SEALED.size, sealed.size)
        }
        override suspend fun upload(name: String, bytes: ByteArray) = "file${files.size + 1}".also { files[it] = bytes.copyOf() }
        override suspend fun download(fileId: String) = files[fileId]?.copyOf() ?: throw IOException("No such file")
        override suspend fun delete(fileId: String) {
            files.remove(fileId)
        }
        companion object {
            val SEALED = "SEALED:".toByteArray()
        }
    }

    private fun export(format: MemoryExport.Format, includeDrive: Boolean, cloud: suspend () -> ArchiveCloud = { drive }) =
        runBlocking {
            val writer = StringWriter()
            val summary = MemoryExporter(db).export(
                writer,
                MemoryExporter.Options(
                    format, includeDrive,
                    car = MemoryExport.Car("Corolla", "AA:BB:CC"),
                    places = listOf(MemoryExport.Place("Home", 51.5, -0.1)),
                ),
                cloud,
            )
            summary to writer.toString()
        }

    private fun state() = runBlocking {
        Triple(db.dao().countNow(), db.archiveDao().storedNow().map { it.id to it.state }, drive.files.keys.toSet())
    }

    @Test
    fun json_hasEveryMessageOnce_inOrder_andChangesNothing() {
        val before = state()
        val (summary, text) = export(MemoryExport.Format.JSON, includeDrive = true)

        assertTrue("Nothing was in Drive", summary.fromDrive > 0)
        assertTrue("Nothing was compressed on the phone", summary.fromPhoneArchive > 0)
        assertEquals(MemoryArchiver.KEEP_NEWEST, summary.fromMemory)
        assertEquals(200, summary.total)
        assertTrue(summary.skipped.isEmpty())

        val json = JSONObject(text)
        val messages = json.getJSONArray("messages")
        assertEquals(200, messages.length())
        val times = List(messages.length()) { messages.getJSONObject(it).getLong("timeMillis") }
        assertEquals(times.sorted(), times)
        val texts = List(messages.length()) { messages.getJSONObject(it).getString("text") }
        assertEquals(originals.map { it.text }, texts)
        assertEquals("Corolla", json.getJSONObject("car").getString("name"))
        assertEquals("Home", json.getJSONArray("places").getJSONObject(0).getString("name"))
        assertEquals(200, json.getJSONObject("summary").getInt("messages"))

        assertEquals(before, state())
    }

    @Test
    fun text_hasEveryMessage_butNoPlacesOrCar() {
        val (summary, text) = export(MemoryExport.Format.TEXT, includeDrive = true)
        assertEquals(200, summary.total)
        assertEquals(200, Regex("Test note \\d+ of 200").findAll(text).count())
        assertTrue(!text.contains("Corolla") && !text.contains("Home"))
    }

    @Test
    fun driveSwitchedOff_leavesDriveOut_andSaysSo() {
        val (summary, _) = export(MemoryExport.Format.JSON, includeDrive = false) { error("Drive shouldn't be asked") }
        assertEquals(0, summary.fromDrive)
        assertTrue(summary.total < 200)
        assertTrue(summary.skipped.single(), summary.skipped.single().contains("chose not to include"))
    }

    @Test
    fun driveUnavailable_exportsTheRest() {
        val before = state()
        val (summary, text) = export(MemoryExport.Format.JSON, includeDrive = true) { throw CloudUnavailable("No network.") }
        assertEquals(0, summary.fromDrive)
        assertTrue(summary.fromMemory + summary.fromPhoneArchive > 0)
        assertTrue(summary.skipped.single(), summary.skipped.single().endsWith("No network."))
        assertEquals(summary.total, JSONObject(text).getJSONArray("messages").length())
        assertEquals(before, state())
    }

    @Test
    fun oldPassphrase_isSkipped_withAHint() {
        drive.lockedWithOldPassphrase = true
        val (summary, _) = export(MemoryExport.Format.TEXT, includeDrive = true)
        assertEquals(0, summary.fromDrive)
        assertTrue(summary.skipped.single(), summary.skipped.single().contains("earlier passphrase"))
    }

    private companion object {
        const val TEST_FILE = "memory-export-test.db"
    }
}
