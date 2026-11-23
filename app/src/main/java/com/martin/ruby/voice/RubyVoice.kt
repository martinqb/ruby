package com.martin.ruby.voice

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** How Ruby's voice sounds. 1.0 is the engine's normal speed or pitch. */
data class VoiceStyle(val speed: Float = 1.0f, val pitch: Float = 1.0f)

/**
 * Ruby's voice: anything that can speak text aloud. The rest of the app only uses this,
 * so a different voice (e.g. a custom HAL-style one) is a new class plus a [VoiceEngine]
 * entry; nothing else changes.
 *
 * Calls come from the main thread. Text is queued: [say] adds to what's being spoken.
 */
interface RubyVoice {

    /** True while anything is being spoken or waiting to be. */
    val speaking: StateFlow<Boolean>

    /** Why the voice can't speak (e.g. no text-to-speech engine), or null if it can. */
    val problem: StateFlow<String?>

    /** Speaks [text] after whatever is already queued. */
    fun say(text: String)

    /** Stops speaking now and drops everything queued. */
    fun stop()

    fun setStyle(style: VoiceStyle)

    /** Frees the engine. The voice can't be used afterwards. */
    fun close()
}

/** The voices Ruby can use. A custom voice adds an entry here and a case in [create]. */
enum class VoiceEngine(val label: String) {
    ANDROID("Android text-to-speech");

    fun create(context: Context): RubyVoice = when (this) {
        ANDROID -> AndroidTtsVoice(context.applicationContext)
    }
}
