package com.martin.ruby.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import com.martin.ruby.ui.orb.OrbTheme
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
    /** Kokoro (af_heart) is Ruby's default voice; Android text-to-speech is used until it's downloaded. */
    val engine: VoiceEngine = VoiceEngine.KOKORO,
    val kokoroSpeaker: KokoroSpeaker = KokoroSpeaker.HEART,
    val style: VoiceStyle = VoiceStyle(),
    /** Ruby's animated orb on the main screen. */
    val showOrb: Boolean = true,
    /** The orb's colors. */
    val orbTheme: OrbTheme = OrbTheme.AURORA,
)

/** Ruby's voice settings, saved with the app's other settings. */
class VoiceSettings(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("voice_mode")
        // A new key: before Kokoro, "voice_engine" always held the only choice there was
        // (Android), which would otherwise keep everyone off Kokoro, the new default.
        val ENGINE = stringPreferencesKey("voice_engine_2")
        val KOKORO_SPEAKER = stringPreferencesKey("voice_kokoro_speaker")
        val SPEED = floatPreferencesKey("voice_speed")
        val PITCH = floatPreferencesKey("voice_pitch")
        val SHOW_ORB = booleanPreferencesKey("show_orb")
        val ORB_THEME = stringPreferencesKey("orb_theme")
    }

    val prefs: Flow<VoicePrefs> = context.dataStore.data.map { p ->
        VoicePrefs(
            mode = p[Keys.MODE]?.let { name -> SpeakMode.entries.firstOrNull { it.name == name } } ?: SpeakMode.AUTO,
            engine = p[Keys.ENGINE]?.let { name -> VoiceEngine.entries.firstOrNull { it.name == name } } ?: VoiceEngine.KOKORO,
            kokoroSpeaker = p[Keys.KOKORO_SPEAKER]?.let { name -> KokoroSpeaker.entries.firstOrNull { it.name == name } }
                ?: KokoroSpeaker.HEART,
            style = VoiceStyle(speed = p[Keys.SPEED] ?: 1.0f, pitch = p[Keys.PITCH] ?: 1.0f),
            showOrb = p[Keys.SHOW_ORB] ?: true,
            orbTheme = p[Keys.ORB_THEME]?.let { name -> OrbTheme.entries.firstOrNull { it.name == name } } ?: OrbTheme.AURORA,
        )
    }

    suspend fun save(prefs: VoicePrefs) {
        context.dataStore.edit { p ->
            p[Keys.MODE] = prefs.mode.name
            p[Keys.ENGINE] = prefs.engine.name
            p[Keys.KOKORO_SPEAKER] = prefs.kokoroSpeaker.name
            p[Keys.SPEED] = prefs.style.speed
            p[Keys.PITCH] = prefs.style.pitch
            p[Keys.SHOW_ORB] = prefs.showOrb
            p[Keys.ORB_THEME] = prefs.orbTheme.name
        }
    }
}
