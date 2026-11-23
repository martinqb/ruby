package com.martin.ruby.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {

    // Few rounds so the tests are quick; the app uses 600,000.
    private val rounds = 1_000
    private val passphrase = "correct horse battery staple"
    private val contents = "the memory database".toByteArray()

    private fun backup(): Pair<BackupCrypto.Setup, ByteArray> {
        val setup = BackupCrypto.setUp(passphrase, rounds)
        return setup to BackupCrypto.seal(setup.header, setup.backupKey, contents)
    }

    private fun assertCantOpen(backup: ByteArray, secret: String) {
        try {
            BackupCrypto.open(backup, secret)
            fail("Expected CantOpen")
        } catch (expected: BackupCrypto.CantOpen) {
        }
    }

    @Test
    fun openWithKey_opensWithThePhonesKey() {
        val (setup, file) = backup()
        assertArrayEquals(contents, BackupCrypto.openWithKey(file, setup.backupKey))
    }

    @Test
    fun openWithKey_fromAnEarlierSetup_isRefused_butItsPassphraseStillWorks() {
        val (_, file) = backup()
        val later = BackupCrypto.setUp(passphrase, rounds) // set up again: a new key
        try {
            BackupCrypto.openWithKey(file, later.backupKey)
            fail("Expected CantOpen")
        } catch (expected: BackupCrypto.CantOpen) {
        }
        assertArrayEquals(contents, BackupCrypto.open(file, passphrase).contents)
    }

    @Test
    fun passphrase_opensTheBackup() {
        val (_, file) = backup()
        assertArrayEquals(contents, BackupCrypto.open(file, passphrase).contents)
    }

    @Test
    fun recoveryKey_opensTheBackup() {
        val (setup, file) = backup()
        assertArrayEquals(contents, BackupCrypto.open(file, setup.recoveryKey).contents)
    }

    @Test
    fun recoveryKey_typedSloppily_stillWorks() {
        val (setup, file) = backup()
        val sloppy = setup.recoveryKey.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
        assertArrayEquals(contents, BackupCrypto.open(file, sloppy).contents)
    }

    @Test
    fun wrongSecrets_dontOpenIt() {
        val (_, file) = backup()
        assertCantOpen(file, "correct horse battery stapler")
        assertCantOpen(file, "short")
        assertCantOpen(file, BackupCrypto.setUp(passphrase, rounds).recoveryKey) // someone else's key
    }

    @Test
    fun aChangedByte_isRefused() {
        val (_, file) = backup()
        for (position in listOf(20, BackupCrypto.HEADER_BYTES + 3, file.size - 1)) { // header, IV, data
            val damaged = file.copyOf().also { it[position] = (it[position] + 1).toByte() }
            assertCantOpen(damaged, passphrase)
        }
    }

    @Test
    fun notABackup_isRefused() {
        assertCantOpen(ByteArray(10), passphrase)
        assertCantOpen(ByteArray(500), passphrase)
    }

    @Test
    fun openedBackup_givesTheKeysToCarryOn() {
        // After a restore, new backups use the restored keys, so the same secrets keep working.
        val (setup, file) = backup()
        val opened = BackupCrypto.open(file, passphrase)
        assertArrayEquals(setup.backupKey, opened.backupKey)
        val next = BackupCrypto.seal(opened.header, opened.backupKey, "newer".toByteArray())
        assertEquals("newer", String(BackupCrypto.open(next, setup.recoveryKey).contents))
    }

    @Test
    fun sameContents_encryptDifferentlyEachTime() {
        val setup = BackupCrypto.setUp(passphrase, rounds)
        val a = BackupCrypto.seal(setup.header, setup.backupKey, contents)
        val b = BackupCrypto.seal(setup.header, setup.backupKey, contents)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun shortPassphrase_isRejectedAtSetUp() {
        try {
            BackupCrypto.setUp("too short", rounds)
            fail("Expected a short passphrase to be rejected")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // RecoveryKey

    @Test
    fun recoveryKey_looksRight() {
        val key = BackupCrypto.setUp(passphrase, rounds).recoveryKey
        assertTrue(key, Regex("([0-9A-HJKMNP-TV-Z]{4}-){7}[0-9A-HJKMNP-TV-Z]{4}").matches(key))
    }

    @Test
    fun recoveryKey_roundTrips() {
        val bytes = ByteArray(RecoveryKey.BYTES) { (it * 37 + 5).toByte() }
        assertArrayEquals(bytes, RecoveryKey.parse(RecoveryKey.format(bytes)))
    }

    @Test
    fun recoveryKey_parse_rejectsOtherText() {
        assertNull(RecoveryKey.parse(passphrase))
        assertNull(RecoveryKey.parse("UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-UUUU-UUUU")) // U isn't used
        assertNotNull(RecoveryKey.parse("0000-0000-0000-0000-0000-0000-0000-0000"))
    }

    // BackupContents

    @Test
    fun contents_packAndUnpack() {
        val original = BackupContents(
            databaseKey = ByteArray(32) { it.toByte() },
            createdMillis = 1_763_800_000_000,
            messageCount = 42,
            database = ByteArray(10_000) { (it % 7).toByte() },
            wal = ByteArray(0),
        )
        val packed = original.pack()
        assertTrue("compressed", packed.size < original.database.size)
        val back = BackupContents.unpack(packed)
        assertArrayEquals(original.databaseKey, back.databaseKey)
        assertEquals(original.createdMillis, back.createdMillis)
        assertEquals(42, back.messageCount)
        assertArrayEquals(original.database, back.database)
        assertEquals(0, back.wal.size)
    }

    @Test(expected = BackupCrypto.CantOpen::class)
    fun contents_damaged_isRefused() {
        BackupContents.unpack("not gzip".toByteArray())
    }
}
