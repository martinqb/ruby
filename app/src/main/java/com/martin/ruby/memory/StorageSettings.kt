package com.martin.ruby.memory

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** What the last storage check did, for the Storage screen. */
data class StorageNote(val timeMillis: Long, val text: String, val problem: Boolean)

/** The storage limit setting, and the last check's result, saved in DataStore. */
class StorageSettings(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("storage_mode")
        val PERCENT = doublePreferencesKey("storage_percent")
        val FIXED = longPreferencesKey("storage_fixed_bytes")
        val TINY = booleanPreferencesKey("storage_tiny_test")
        val WARNED = booleanPreferencesKey("storage_warned")
        val NOTE_TIME = longPreferencesKey("storage_note_time")
        val NOTE = stringPreferencesKey("storage_note")
        val NOTE_PROBLEM = booleanPreferencesKey("storage_note_problem")
    }

    val budget: Flow<BudgetSetting> = context.dataStore.data.map(::readBudget)

    val note: Flow<StorageNote?> = context.dataStore.data.map { prefs ->
        val text = prefs[Keys.NOTE] ?: return@map null
        StorageNote(prefs[Keys.NOTE_TIME] ?: 0L, text, prefs[Keys.NOTE_PROBLEM] ?: false)
    }

    suspend fun currentBudget(): BudgetSetting = budget.first()

    suspend fun setBudget(setting: BudgetSetting) {
        context.dataStore.edit {
            it[Keys.MODE] = setting.mode.name
            it[Keys.PERCENT] = setting.percent
            it[Keys.FIXED] = setting.fixedBytes
            it[Keys.TINY] = setting.tinyTest
        }
    }

    /** Returns true the first time memory passes 80% since it was last below. */
    suspend fun markWarned(): Boolean {
        var first = false
        context.dataStore.edit {
            first = it[Keys.WARNED] != true
            it[Keys.WARNED] = true
        }
        return first
    }

    suspend fun clearWarned() {
        context.dataStore.edit { it[Keys.WARNED] = false }
    }

    suspend fun setNote(text: String, problem: Boolean = false) {
        context.dataStore.edit {
            it[Keys.NOTE_TIME] = System.currentTimeMillis()
            it[Keys.NOTE] = text
            it[Keys.NOTE_PROBLEM] = problem
        }
    }

    private fun readBudget(prefs: Preferences) = BudgetSetting(
        mode = prefs[Keys.MODE]?.let { runCatching { BudgetSetting.Mode.valueOf(it) }.getOrNull() }
            ?: BudgetSetting.Mode.PERCENT,
        percent = prefs[Keys.PERCENT] ?: StorageBudget.DEFAULT_PERCENT,
        fixedBytes = prefs[Keys.FIXED] ?: 0L,
        tinyTest = prefs[Keys.TINY] ?: false,
    )
}
