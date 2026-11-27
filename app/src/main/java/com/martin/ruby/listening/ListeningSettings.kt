package com.martin.ruby.listening

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The listening choices, saved on the phone with Ruby's other settings. */
class ListeningSettings(context: Context) {

    private val app = context.applicationContext

    private object Keys {
        val ON = booleanPreferencesKey("listening_on")
        val CONVERSATION = booleanPreferencesKey("listening_conversation")
        val WAKE_WORD = booleanPreferencesKey("listening_wake_word")
        val WAKE_PHRASE = stringPreferencesKey("listening_wake_phrase")
        val FULL_DUPLEX = booleanPreferencesKey("listening_full_duplex")
    }

    val prefs: Flow<ListeningPrefs> = app.dataStore.data.map { p ->
        ListeningPrefs(
            on = p[Keys.ON] ?: true,
            conversation = p[Keys.CONVERSATION] ?: false,
            wakeWord = p[Keys.WAKE_WORD] ?: false,
            wakePhrase = p[Keys.WAKE_PHRASE]?.takeIf { WakePhrase.problem(it) == null } ?: WakePhrase.DEFAULT,
            fullDuplex = p[Keys.FULL_DUPLEX] ?: false,
        )
    }

    suspend fun current(): ListeningPrefs = prefs.first()

    suspend fun save(prefs: ListeningPrefs) {
        app.dataStore.edit { p ->
            p[Keys.ON] = prefs.on
            p[Keys.CONVERSATION] = prefs.conversation
            p[Keys.WAKE_WORD] = prefs.wakeWord
            p[Keys.WAKE_PHRASE] = prefs.wakePhrase.trim()
            p[Keys.FULL_DUPLEX] = prefs.fullDuplex
        }
    }

    suspend fun update(change: (ListeningPrefs) -> ListeningPrefs) = save(change(current()))
}
