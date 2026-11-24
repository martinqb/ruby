package com.martin.ruby.radio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.martin.ruby.log.SensingLog

/**
 * Plays the radio: Android's media player (ExoPlayer) in a media service, so it keeps playing
 * with the screen off and shows the usual player controls. Its media session takes the car's
 * steering-wheel buttons, a headset's and a voice assistant's pause. It also:
 * - turns itself down while Ruby talks (Android's audio focus: her voice asks others to duck),
 * - stops when the car's Bluetooth audio disconnects ("becoming noisy"), so it doesn't carry on
 *   from the phone's speaker.
 */
class RadioService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        session = MediaSession.Builder(this, player).setId("ruby-radio").build()
        // Started from the background (after the car briefing), Android may refuse the player
        // notification's foreground service; the radio still plays while Ruby's sensing runs.
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                SensingLog.add("Radio: playing without its notification (started in the background).")
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
