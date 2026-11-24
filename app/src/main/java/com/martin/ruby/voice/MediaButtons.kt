package com.martin.ruby.voice

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.martin.ruby.log.SensingLog

/**
 * Stops Ruby hands-free while she's talking: the car's steering-wheel pause button, a
 * headset button, or a voice assistant's pause ("Hi Bixby, pause" on Samsung, "Hey Google,
 * stop" elsewhere), which presses pause for whatever is playing. While
 * she talks she shows as playing, like a music app, so those come to her; once she's done,
 * they go back to the music app.
 *
 * Ruby can't listen for "stop" herself: Android doesn't let an app turn on the microphone
 * from the background, which is how the briefing starts when the car connects.
 *
 * Main thread only.
 */
class MediaButtons(context: Context, private val onStop: () -> Unit) {

    private val session = MediaSession(context.applicationContext, "Ruby").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPause() = stopped("pause")
            override fun onStop() = stopped("stop")

            // Play/pause and a headset's single button toggle; while she's talking that means stop.
            override fun onPlay() = stopped("play")

            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                if (key?.action == KeyEvent.ACTION_DOWN && key.keyCode in STOP_KEYS) {
                    stopped(KeyEvent.keyCodeToString(key.keyCode))
                    return true
                }
                return super.onMediaButtonEvent(intent)
            }
        })
        setPlaybackState(state(PlaybackState.STATE_STOPPED))
    }

    private var talking = false

    /** She started or stopped talking. [what] is shown on the car's screen, e.g. "Morning briefing". */
    fun talking(now: Boolean, what: String = "Ruby is talking") {
        if (now == talking) return
        talking = now
        if (now) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, what)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Ruby")
                    .build()
            )
            session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            session.isActive = true
        } else {
            session.setPlaybackState(state(PlaybackState.STATE_STOPPED))
            session.isActive = false // the buttons go back to the music app
        }
    }

    fun release() = session.release()

    private fun stopped(how: String) {
        if (!talking) return
        SensingLog.add("Voice: stopped hands-free ($how).")
        onStop()
    }

    private fun state(state: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
        .build()

    private companion object {
        val STOP_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY,
        )
    }
}
