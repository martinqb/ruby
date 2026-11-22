package com.martin.ruby.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saves small secrets (keys) in a file, encrypted ("wrapped") by a key that lives in the
 * Android Keystore. The Keystore never lets its keys leave the phone's secure hardware,
 * so the file is useless anywhere else, including in a copy of the phone's storage.
 *
 * Each [alias] is its own Keystore key. No fingerprint or screen lock is needed to use
 * it: the background service and nightly backups have to work while the phone is locked.
 */
object KeystoreBox {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128

    /** Thrown when a saved secret can't be unwrapped, e.g. the Keystore key was lost. */
    class Unavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Encrypts [secret] with the Keystore key [alias] (made if needed) and saves it to [file]. */
    fun save(alias: String, file: File, secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(alias))
        val iv = cipher.iv
        val wrapped = cipher.doFinal(secret)
        // Saved as: IV length (1 byte), IV, encrypted secret.
        file.writeBytes(byteArrayOf(iv.size.toByte()) + iv + wrapped)
    }

    /** Reads and decrypts a secret saved by [save]. */
    fun load(alias: String, file: File): ByteArray = try {
        val saved = file.readBytes()
        val ivLength = saved[0].toInt()
        val iv = saved.copyOfRange(1, 1 + ivLength)
        val wrapped = saved.copyOfRange(1 + ivLength, saved.size)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey
            ?: throw Unavailable("The phone's Keystore no longer has Ruby's key \"$alias\".")
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(wrapped)
        }
    } catch (e: Unavailable) {
        throw e
    } catch (e: Exception) {
        throw Unavailable("Couldn't unlock a saved key: ${e.javaClass.simpleName}.", e)
    }

    private fun keyFor(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
