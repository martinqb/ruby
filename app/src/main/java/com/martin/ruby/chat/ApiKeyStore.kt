package com.martin.ruby.chat

import android.content.Context
import com.martin.ruby.security.KeystoreBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's Anthropic API key, which Ruby uses to talk to Claude.
 *
 * It's saved wrapped by a Keystore key ([KeystoreBox]) in no_backup, so Android's own
 * backup never copies it and the file can't be read off this phone. It isn't in Ruby's
 * Drive backups either: on a new phone the user pastes it in again.
 */
class ApiKeyStore(context: Context) {

    private val file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    suspend fun hasKey(): Boolean = withContext(Dispatchers.IO) { file.exists() }

    /** The saved key, or null if there isn't one (or it can't be unlocked any more). */
    suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            String(KeystoreBox.load(WRAP_ALIAS, file), Charsets.UTF_8)
        } catch (e: KeystoreBox.Unavailable) {
            null
        }
    }

    suspend fun save(key: String) = withContext(Dispatchers.IO) {
        KeystoreBox.save(WRAP_ALIAS, file, key.trim().toByteArray(Charsets.UTF_8))
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

    companion object {
        private const val WRAP_ALIAS = "ruby_api_key_wrap"
        private const val FILE_NAME = "anthropic_key.bin"

        /** Anthropic API keys start like this; used to catch obvious paste mistakes. */
        const val KEY_PREFIX = "sk-ant-"
    }
}
