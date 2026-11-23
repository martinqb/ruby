package com.martin.ruby.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a phone or emulator: checks SQLCipher encryption and FTS5 search really work.
 * Uses its own database file, so it's safe to run on a phone with real memory on it.
 */
@RunWith(AndroidJUnit4::class)
class MemoryDatabaseTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var db = MemoryDatabase.open(context, TEST_FILE)

    @After
    fun cleanUp() {
        db.close()
        context.deleteDatabase(TEST_FILE)
    }

    private suspend fun search(dao: MemoryDao, typed: String) =
        dao.search(MemoryDatabase.searchQuery(MemorySearch.ftsQuery(typed)!!)).first()

    @Test
    fun save_search_delete() = runBlocking {
        val dao = db.dao()
        val parked = dao.insert(MemoryMessage(timeMillis = 1, role = MemoryMessage.ROLE_USER, text = "Parked on level 3, row C"))
        dao.insert(MemoryMessage(timeMillis = 2, role = MemoryMessage.ROLE_USER, text = "Tyre pressure is 36 psi"))

        assertEquals(listOf(parked), search(dao, "park level").map { it.id }) // "park" finds "Parked"
        assertEquals(1, search(dao, "PSI").size)
        assertTrue(search(dao, "garage").isEmpty())

        dao.delete(parked)
        assertTrue(search(dao, "park").isEmpty()) // the index follows deletes
        dao.deleteAll()
        assertEquals(0, dao.count().first())
    }

    @Test
    fun file_isEncrypted() = runBlocking {
        db.dao().insert(MemoryMessage(timeMillis = 3, role = MemoryMessage.ROLE_USER, text = "plaintext-canary-7731"))
        db.close() // writes everything into the main file

        val bytes = context.getDatabasePath(TEST_FILE).readBytes()
        assertFalse("Unencrypted SQLite header", String(bytes, 0, 15, Charsets.US_ASCII) == "SQLite format 3")
        assertFalse("Message text visible in the file", String(bytes, Charsets.ISO_8859_1).contains("plaintext-canary-7731"))

        // And it still opens with the key.
        db = MemoryDatabase.open(context, TEST_FILE)
        assertEquals(1, search(db.dao(), "canary").size)
    }

    private companion object {
        const val TEST_FILE = "memory-test.db"
    }
}
