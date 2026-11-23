package com.martin.ruby.voice

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** When Ruby reads her replies aloud. */
enum class SpeakMode(val label: String, val explained: String) {
    OFF("Off", "Ruby never reads replies aloud. You can still tap Play on any reply."),
    AUTO("Auto", "When you talk to her, and while you're driving or in a vehicle."),
    ALWAYS("Always", "Every reply is read aloud."),
}

data class VoicePrefs(
    val mode: SpeakMode = SpeakMode.AUTO,
    val engine: VoiceEngine = VoiceEngine.ANDROID,
    val style: VoiceStyle = VoiceStyle(),
)

/** Ruby's voice settings, saved with the app's other settings. */
class VoiceSettings(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("voice_mode")
        val ENGINE = stringPreferencesKey("voice_engine")
        val SPEED = floatPreferencesKey("voice_speed")
        val PITCH = floatPreferencesKey("voice_pitch")
    }

    val prefs: Flow<VoicePrefs> = context.dataStore.data.map { p ->
        VoicePrefs(
            mode = p[Keys.MODE]?.let { name -> SpeakMode.entries.firstOrNull { it.name == name } } ?: SpeakMode.AUTO,
            engine = p[Keys.ENGINE]?.let { name -> VoiceEngine.entries.firstOrNull { it.name == name } } ?: VoiceEngine.ANDROID,
            style = VoiceStyle(speed = p[Keys.SPEED] ?: 1.0f, pitch = p[Keys.PITCH] ?: 1.0f),
        )
    }

    suspend fun save(prefs: VoicePrefs) {
        context.dataStore.edit { p ->
            p[Keys.MODE] = prefs.mode.name
            p[Keys.ENGINE] = prefs.engine.name
            p[Keys.SPEED] = prefs.style.speed
            p[Keys.PITCH] = prefs.style.pitch
        }
    }
}
