package com.martin.ruby.backup

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How Google Drive backups have been going, for the Backup screen. */
data class BackupStatus(
    /** The user turned on the nightly backup. */
    val nightlyOn: Boolean = false,
    /** Last successful Drive backup (wall-clock time), or null if there's never been one. */
    val lastSuccessMillis: Long? = null,
    val lastMessageCount: Int = 0,
    val lastSizeBytes: Long = 0,
    /** The last try that failed, and why. Cleared by a success. */
    val lastFailureMillis: Long? = null,
    val lastError: String? = null,
    /** Google wants the user to approve Drive access again before backups can continue. */
    val needsApproval: Boolean = false,
)

/** Saves [BackupStatus] in DataStore, so it's there after a restart. */
class BackupStatusStore(private val context: Context) {

    private object Keys {
        val NIGHTLY_ON = booleanPreferencesKey("backup_nightly_on")
        val LAST_SUCCESS = longPreferencesKey("backup_last_success")
        val LAST_COUNT = intPreferencesKey("backup_last_count")
        val LAST_SIZE = longPreferencesKey("backup_last_size")
        val LAST_FAILURE = longPreferencesKey("backup_last_failure")
        val LAST_ERROR = stringPreferencesKey("backup_last_error")
        val NEEDS_APPROVAL = booleanPreferencesKey("backup_needs_approval")
    }

    val status: Flow<BackupStatus> = context.dataStore.data.map(::read)

    suspend fun current(): BackupStatus = status.first()

    suspend fun setNightlyOn(on: Boolean) {
        context.dataStore.edit { it[Keys.NIGHTLY_ON] = on }
    }

    suspend fun recordSuccess(messageCount: Int, sizeBytes: Long) {
        context.dataStore.edit {
            it[Keys.LAST_SUCCESS] = System.currentTimeMillis()
            it[Keys.LAST_COUNT] = messageCount
            it[Keys.LAST_SIZE] = sizeBytes
            it.remove(Keys.LAST_FAILURE)
            it.remove(Keys.LAST_ERROR)
            it[Keys.NEEDS_APPROVAL] = false
        }
    }

    suspend fun recordFailure(error: String) {
        context.dataStore.edit {
            it[Keys.LAST_FAILURE] = System.currentTimeMillis()
            it[Keys.LAST_ERROR] = error
        }
    }

    suspend fun recordNeedsApproval() {
        context.dataStore.edit {
            it[Keys.LAST_FAILURE] = System.currentTimeMillis()
            it[Keys.LAST_ERROR] = "Google Drive access needs approving again."
            it[Keys.NEEDS_APPROVAL] = true
        }
    }

    private fun read(prefs: Preferences) = BackupStatus(
        nightlyOn = prefs[Keys.NIGHTLY_ON] ?: false,
        lastSuccessMillis = prefs[Keys.LAST_SUCCESS],
        lastMessageCount = prefs[Keys.LAST_COUNT] ?: 0,
        lastSizeBytes = prefs[Keys.LAST_SIZE] ?: 0,
        lastFailureMillis = prefs[Keys.LAST_FAILURE],
        lastError = prefs[Keys.LAST_ERROR],
        needsApproval = prefs[Keys.NEEDS_APPROVAL] ?: false,
    )
}
