package com.martin.ruby.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key that encrypts the memory database.
 *
 * It's 32 random bytes, made once on this phone. It's never stored as-is: it's saved
 * encrypted ("wrapped") by a second key that lives in the Android Keystore, which never
 * lets that second key leave the phone's secure hardware. So the database file alone,
 * or the database plus the wrapped key file, can't be read anywhere else.
 *
 * A Google Drive backup (later in Phase 6) carries this key inside the backup, encrypted
 * with the user's passphrase, so a new phone can open the restored database.
 */
object MemoryKey {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_ALIAS = "ruby_memory_wrap"
    private const val FILE_NAME = "memory_key.bin"
    private const val KEY_BYTES = 32
    private const val GCM_TAG_BITS = 128

    /** Thrown when the saved key can't be unwrapped, e.g. the Keystore key was lost. */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Returns the database key, making one the first time. Call off the main thread. */
    @Synchronized
    fun get(context: Context): ByteArray {
        val file = File(context.noBackupFilesDir, FILE_NAME)
        if (file.exists()) return unwrap(file.readBytes())
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        save(context, key)
        return key
    }

    /** Replaces the database key, e.g. with the one from a restored backup. */
    @Synchronized
    fun save(context: Context, key: ByteArray) {
        require(key.size == KEY_BYTES) { "A memory key is $KEY_BYTES bytes." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv
        val wrapped = cipher.doFinal(key)
        // Saved as: IV length (1 byte), IV, encrypted key.
        File(context.noBackupFilesDir, FILE_NAME).writeBytes(byteArrayOf(iv.size.toByte()) + iv + wrapped)
    }

    private fun unwrap(saved: ByteArray): ByteArray = try {
        val ivLength = saved[0].toInt()
        val iv = saved.copyOfRange(1, 1 + ivLength)
        val wrapped = saved.copyOfRange(1 + ivLength, saved.size)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val wrapKey = keyStore.getKey(WRAP_ALIAS, null) as? SecretKey
            ?: throw Unavailable("The phone's Keystore no longer has Ruby's key.")
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(wrapped)
        }
    } catch (e: Unavailable) {
        throw e
    } catch (e: Exception) {
        throw Unavailable("Couldn't unlock the memory key: ${e.javaClass.simpleName}.", e)
    }

    /** The Keystore key that wraps the database key, made the first time. */
    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No fingerprint or screen lock needed: the background service and the
                // nightly backup have to open the database while the phone is locked.
                .build()
        )
        return generator.generateKey()
    }
}
