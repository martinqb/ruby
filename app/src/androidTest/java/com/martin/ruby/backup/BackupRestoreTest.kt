package com.martin.ruby.backup

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.memory.MemoryDatabase
import com.martin.ruby.memory.MemoryMessage
import com.martin.ruby.memory.MemoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole cycle on a device: set up, back up, wipe everything as if on a new phone,
 * restore with the recovery key, and check the passphrase opens the next backup.
 *
 * It wipes Ruby's real memory, so it only runs on an emulator and skips itself on a phone.
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val passphrase = "correct horse battery staple"

    private fun isEmulator() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")

    @Test
    fun backup_wipe_restore() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        val store = MemoryStore(context)
        val manager = BackupManager(context)
        MemoryDatabase.get(context).dao().deleteAll()
        store.add(MemoryMessage.ROLE_USER, "Parked on level 3, row C")
        store.add(MemoryMessage.ROLE_USER, "Tyre pressure is 36 psi")

        val started = SystemClock.elapsedRealtime()
        val recoveryKey = manager.setUp(passphrase)
        Log.i("BackupRestoreTest", "Set up (600,000 PBKDF2 rounds) took ${SystemClock.elapsedRealtime() - started} ms")
        val backup = manager.makeBackup()
        assertEquals(2, backup.messageCount)

        // A new phone: no database, no memory key, no backup setup.
        MemoryDatabase.close()
        context.deleteDatabase(MemoryDatabase.FILE_NAME)
        listOf("memory_key.bin", "backup_key.bin", "backup_header.bin").forEach { File(context.noBackupFilesDir, it).delete() }
        assertFalse(manager.isSetUp())

        try {
            manager.unlock(backup.bytes, "not the passphrase at all")
            fail("A wrong passphrase opened the backup")
        } catch (expected: BackupCrypto.CantOpen) {
        }

        val unlocked = manager.unlock(backup.bytes, recoveryKey)
        assertEquals(2, unlocked.messageCount)
        assertEquals(2, manager.restore(unlocked))

        // The messages and the search index came back.
        val found = store.search("park").first()
        assertEquals(listOf("Parked on level 3, row C"), found.map { it.text })
        assertTrue(manager.isSetUp())

        // Backups made after the restore still open with the original passphrase.
        store.add(MemoryMessage.ROLE_USER, "Added after the restore")
        val next = manager.makeBackup()
        assertEquals(3, manager.unlock(next.bytes, passphrase).messageCount)

        MemoryDatabase.get(context).dao().deleteAll()
    }
}
