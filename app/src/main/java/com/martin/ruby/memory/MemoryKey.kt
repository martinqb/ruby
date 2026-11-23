package com.martin.ruby.memory

import android.content.Context
import com.martin.ruby.security.KeystoreBox
import java.io.File
import java.security.SecureRandom

/**
 * The key that encrypts the memory database.
 *
 * It's 32 random bytes, made once on this phone, and saved wrapped by a Keystore key
 * ([KeystoreBox]), so the database file alone, or the database plus the wrapped key file,
 * can't be read anywhere else.
 *
 * A backup carries this key inside it, encrypted with the user's passphrase or recovery
 * key, so a new phone can open the restored database; [save] then stores it there.
 */
object MemoryKey {

    private const val WRAP_ALIAS = "ruby_memory_wrap"
    private const val FILE_NAME = "memory_key.bin"
    const val KEY_BYTES = 32

    /** Returns the database key, making one the first time. Call off the main thread. */
    @Synchronized
    fun get(context: Context): ByteArray {
        val file = file(context)
        if (file.exists()) return KeystoreBox.load(WRAP_ALIAS, file)
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        save(context, key)
        return key
    }

    /** Replaces the database key, e.g. with the one from a restored backup. */
    @Synchronized
    fun save(context: Context, key: ByteArray) {
        require(key.size == KEY_BYTES) { "A memory key is $KEY_BYTES bytes." }
        KeystoreBox.save(WRAP_ALIAS, file(context), key)
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE_NAME)
}
