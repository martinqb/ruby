package com.martin.ruby.ai

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Who writes Ruby's words. With [CLAUDE], everything uses Claude (chat, reminders, the
 * briefing, nightly summaries), as before. [FREE] uses Gemini Nano, built into the phone:
 * private, offline, no cost, with its own rules (see [FreeMode]).
 */
enum class AiEngine(val label: String, val explained: String) {
    CLAUDE("Claude", "Anthropic's Claude, with your own API key. Ruby at her smartest; each message costs a little."),
    FREE("Free mode", "Gemini Nano, the AI built into this phone. Nothing you say leaves the phone to be answered."),
    ;

    companion object {
        /** Under the switch, when this phone has Free mode. */
        const val HELPER =
            "Free mode uses the AI built into your phone: private, offline, no cost. Want Ruby at her smartest? " +
                "Switch to Claude (needs your own API key)."

        /** Under the switch, on phones without Gemini Nano. */
        const val NOT_ON_THIS_PHONE = "Free mode isn't available on this phone."
    }
}

/** The chosen engine, saved with the app's other settings. Claude until changed. */
class AiSettings(private val context: Context) {

    private val key = stringPreferencesKey("ai_engine")

    val engine: Flow<AiEngine> = context.applicationContext.dataStore.data.map { p ->
        p[key]?.let { name -> AiEngine.entries.firstOrNull { it.name == name } } ?: AiEngine.CLAUDE
    }

    suspend fun current(): AiEngine = engine.first()

    suspend fun save(engine: AiEngine) {
        context.applicationContext.dataStore.edit { it[key] = engine.name }
    }
}
