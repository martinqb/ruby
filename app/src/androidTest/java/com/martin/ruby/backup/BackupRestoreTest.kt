package com.martin.ruby.backup

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.memory.MemoryDatabase
import com.martin.ruby.memory.MemoryMessage
import com.martin.ruby.memory.MemoryStore
import com.martin.ruby.reminders.ReminderStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    /**
     * The reinstall case: the new install opens memory before restoring (so it has a fresh,
     * empty memory with its own key), then restores through [RestoreSession]. The backup's
     * messages and reminders must be there, and still there after the app restarts.
     */
    @Test
    fun restore_afterReinstall_whenMemoryWasOpenedFirst() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        val store = MemoryStore(context)
        val reminders = ReminderStore(context)
        val manager = BackupManager(context)
        wipe()
        repeat(48) { store.add(MemoryMessage.ROLE_USER, "Message $it about tyres and parking") }
        val reminder = reminders.add("Check the oven", dueMillis = System.currentTimeMillis() + 3_600_000, place = null)
        val recoveryKey = manager.setUp(passphrase)
        val backup = manager.makeBackup()
        assertEquals(48, backup.messageCount)

        wipe() // uninstalled
        assertEquals(0, store.count().first()) // the new install opens memory first: fresh and empty
        store.add(MemoryMessage.ROLE_USER, "Written on the new install before restoring")

        val unlocked = manager.unlock(backup.bytes, recoveryKey)
        assertTrue(RestoreSession.start(context, unlocked))
        val result = withTimeout(60_000) {
            RestoreSession.state.first { it is RestoreSession.State.Done || it is RestoreSession.State.Failed }
        }
        assertEquals(RestoreSession.State.Done::class, result::class)
        assertEquals(48, (result as RestoreSession.State.Done).count)
        assertEquals(48, store.count().first())
        assertEquals(listOf(reminder.id), reminders.pending().map { it.id })
        assertTrue(manager.isSetUp())

        // After the app restarts, memory is still the restored one.
        MemoryDatabase.close()
        assertEquals(48, store.count().first())
        assertEquals(listOf("Check the oven"), reminders.pending().map { it.text })
        RestoreSession.dismiss()
        wipe()
    }

    /** Leaving the screen used to cancel a restore. Now a cancelled caller doesn't stop it. */
    @Test
    fun restore_finishesEvenIfItsCallerIsCancelled() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        val store = MemoryStore(context)
        val manager = BackupManager(context)
        wipe()
        repeat(5) { store.add(MemoryMessage.ROLE_USER, "Kept $it") }
        val recoveryKey = manager.setUp(passphrase)
        val backup = manager.makeBackup()
        wipe()
        val unlocked = manager.unlock(backup.bytes, recoveryKey)

        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { manager.restore(unlocked) }
        job.cancel() // like the screen going away
        job.join()

        assertEquals(5, store.count().first())
        assertTrue("The backup's key was kept too", manager.isSetUp())
        wipe()
    }

    private fun wipe() {
        MemoryDatabase.close()
        context.deleteDatabase(MemoryDatabase.FILE_NAME)
        listOf("memory_key.bin", "backup_key.bin", "backup_header.bin").forEach { File(context.noBackupFilesDir, it).delete() }
    }
}
