package com.martin.ruby.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption behind Ruby's backups. Plain Java crypto, no Android, so it's unit tested.
 *
 * One random **backup key** encrypts every backup. A copy of it is locked twice and kept
 * in each backup's header:
 *  - with the user's **passphrase**, stretched by PBKDF2 so guessing is slow, and
 *  - with a generated **recovery key**, shown to the user once and never stored.
 * Either one unlocks the backup key, and the backup key opens the backup. Google, or
 * anyone else holding the file, has neither.
 *
 * File layout:
 * ```
 * header   "RUBYBAK1", version, PBKDF2 rounds, salt,
 *          backup key locked by passphrase, backup key locked by recovery key
 * payload  IV + AES-256-GCM(backup key, contents), with the header as authenticated data
 * ```
 * GCM detects any change to the file, including the header, so a damaged or tampered
 * backup is refused rather than restored wrongly.
 */
object BackupCrypto {

    const val KEY_BYTES = 32
    /** OWASP's recommendation for PBKDF2-HMAC-SHA256. About a second on a phone. */
    const val PASSPHRASE_ROUNDS = 600_000
    const val MIN_PASSPHRASE_LENGTH = 12

    private val MAGIC = "RUBYBAK1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val LOCKED_KEY_BYTES = IV_BYTES + KEY_BYTES + TAG_BITS / 8

    /** Size of the header, which is the same for every backup made with the same keys. */
    const val HEADER_BYTES = 8 + 1 + 4 + 16 + 2 * (12 + 32 + 16)

    private val PASSPHRASE_LABEL = "ruby-backup-passphrase".toByteArray()
    private val RECOVERY_LABEL = "ruby-backup-recovery".toByteArray()

    private val random = SecureRandom()

    /** Thrown when a backup can't be opened: wrong passphrase or key, or a damaged file. */
    class CantOpen(message: String) : Exception(message)

    /** The result of setting up backups: what to keep on the phone, and what to show once. */
    class Setup(
        /** Encrypts backups. Kept on the phone, wrapped by the Keystore. */
        val backupKey: ByteArray,
        /** Goes at the start of every backup. Holds the two locked copies of [backupKey]. */
        val header: ByteArray,
        /** Shown to the user once, e.g. "7KQ2-MV9D-...". Never stored. */
        val recoveryKey: String,
    )

    /** Makes a new backup key, locked by [passphrase] and by a new recovery key. */
    fun setUp(passphrase: String, rounds: Int = PASSPHRASE_ROUNDS): Setup {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) { "Passphrase too short." }
        val backupKey = randomBytes(KEY_BYTES)
        val recovery = randomBytes(RecoveryKey.BYTES)
        val salt = randomBytes(SALT_BYTES)

        val header = ByteArrayOutputStream().apply {
            DataOutputStream(this).run {
                write(MAGIC)
                writeByte(VERSION.toInt())
                writeInt(rounds)
                write(salt)
                write(lock(passphraseKey(passphrase, salt, rounds), backupKey, PASSPHRASE_LABEL))
                write(lock(RecoveryKey.wrappingKey(recovery), backupKey, RECOVERY_LABEL))
            }
        }.toByteArray()
        check(header.size == HEADER_BYTES)
        return Setup(backupKey, header, RecoveryKey.format(recovery))
    }

    /** Encrypts [contents] into a complete backup file. */
    fun seal(header: ByteArray, backupKey: ByteArray, contents: ByteArray): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(backupKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        return header + iv + cipher.doFinal(contents)
    }

    /** What [open] found: the contents, plus the keys to keep making backups the same way. */
    class Opened(val contents: ByteArray, val backupKey: ByteArray, val header: ByteArray)

    /**
     * Opens a backup with [secret], which can be either the passphrase or the recovery key:
     * if it looks like a recovery key it's tried as one first, then as a passphrase.
     */
    fun open(backup: ByteArray, secret: String): Opened {
        val header = readHeader(backup)
        val backupKey = unlockBackupKey(header, secret)
            ?: throw CantOpen("That passphrase or recovery key doesn't open this backup.")
        return Opened(decrypt(backup, header, backupKey, "The backup is damaged or has been changed."), backupKey, header.bytes)
    }

    /**
     * Opens a file sealed with a backup key the phone already has, without the passphrase.
     * Throws [CantOpen] if it was sealed with a different key (an earlier setup) or is damaged.
     */
    fun openWithKey(backup: ByteArray, backupKey: ByteArray): ByteArray =
        decrypt(backup, readHeader(backup), backupKey, "This was locked with a different passphrase, or is damaged.")

    private fun decrypt(backup: ByteArray, header: Header, backupKey: ByteArray, failure: String): ByteArray {
        val iv = backup.copyOfRange(HEADER_BYTES, HEADER_BYTES + IV_BYTES)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(backupKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
                updateAAD(header.bytes)
                doFinal(backup, HEADER_BYTES + IV_BYTES, backup.size - HEADER_BYTES - IV_BYTES)
            }
        } catch (e: GeneralSecurityException) {
            throw CantOpen(failure)
        }
    }

    // The header

    private class Header(
        val bytes: ByteArray,
        val rounds: Int,
        val salt: ByteArray,
        val lockedByPassphrase: ByteArray,
        val lockedByRecovery: ByteArray,
    )

    private fun readHeader(backup: ByteArray): Header {
        if (backup.size < HEADER_BYTES + IV_BYTES) throw CantOpen("This isn't a Ruby backup (too short).")
        val bytes = backup.copyOfRange(0, HEADER_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).run {
            val magic = ByteArray(MAGIC.size).also { readFully(it) }
            if (!magic.contentEquals(MAGIC)) throw CantOpen("This isn't a Ruby backup.")
            val version = readByte()
            if (version != VERSION) throw CantOpen("This backup is from a newer Ruby (version $version).")
            val rounds = readInt()
            if (rounds !in 1..10_000_000) throw CantOpen("This backup's header is damaged.")
            val salt = ByteArray(SALT_BYTES).also { readFully(it) }
            val byPassphrase = ByteArray(LOCKED_KEY_BYTES).also { readFully(it) }
            val byRecovery = ByteArray(LOCKED_KEY_BYTES).also { readFully(it) }
            return Header(bytes, rounds, salt, byPassphrase, byRecovery)
        }
    }

    private fun unlockBackupKey(header: Header, secret: String): ByteArray? {
        RecoveryKey.parse(secret)?.let { recovery ->
            unlock(RecoveryKey.wrappingKey(recovery), header.lockedByRecovery, RECOVERY_LABEL)?.let { return it }
        }
        if (secret.length < MIN_PASSPHRASE_LENGTH) return null
        return unlock(passphraseKey(secret, header.salt, header.rounds), header.lockedByPassphrase, PASSPHRASE_LABEL)
    }

    // Locking the backup key

    private fun lock(wrappingKey: ByteArray, secret: ByteArray, label: ByteArray): ByteArray {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(label)
        return iv + cipher.doFinal(secret)
    }

    /** Returns the secret, or null if [wrappingKey] is the wrong key. */
    private fun unlock(wrappingKey: ByteArray, locked: ByteArray, label: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(wrappingKey, "AES"),
                GCMParameterSpec(TAG_BITS, locked, 0, IV_BYTES),
            )
            updateAAD(label)
            doFinal(locked, IV_BYTES, locked.size - IV_BYTES)
        }
    } catch (e: GeneralSecurityException) {
        null
    }

    /**
     * Stretches the passphrase into a key. The same passphrase typed on another phone's
     * keyboard can differ invisibly (accents, full-width characters), so it's normalized
     * first. Spaces and capitals still count.
     */
    private fun passphraseKey(passphrase: String, salt: ByteArray, rounds: Int): ByteArray {
        val normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFKC)
        val spec = PBEKeySpec(normalized.toCharArray(), salt, rounds, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(count: Int) = ByteArray(count).also { random.nextBytes(it) }
}

/**
 * The generated recovery key: 20 random bytes (160 bits, far too many to guess), written
 * as 32 characters in groups of 4, like `7KQ2-MV9D-...`.
 *
 * It uses Crockford's base 32: digits and capital letters without I, L, O or U, so it's
 * easy to read and copy by hand. When typed back in, case, spaces and dashes don't
 * matter, and O is read as 0 and I or L as 1.
 */
object RecoveryKey {

    const val BYTES = 20
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CHARS = BYTES * 8 / 5 // 32

    fun format(bytes: ByteArray): String {
        require(bytes.size == BYTES)
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(buffer shr bits) and 31])
            }
        }
        return out.chunked(4).joinToString("-")
    }

    /** Reads a typed recovery key, or returns null if it isn't one. */
    fun parse(typed: String): ByteArray? {
        val cleaned = typed.uppercase()
            .filterNot { it == '-' || it.isWhitespace() }
            .map { c -> when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c } }
        if (cleaned.size != CHARS) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    /** The AES key made from the recovery key. It's already random, so a hash is enough. */
    fun wrappingKey(recovery: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("ruby-recovery-v1".toByteArray() + recovery)
}
