package com.martin.ruby.radio

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.martin.ruby.log.SensingLog

/**
 * Plays the radio: Android's media player (ExoPlayer) in a media foreground service, so it
 * keeps playing with the screen off and shows the usual player controls. While playing it holds
 * a wake lock and a Wi-Fi lock (network wake mode), so the phone doesn't sleep or drop Wi-Fi
 * under it. Its media session takes the car's steering-wheel buttons, a headset's and a voice
 * assistant's pause. It also:
 * - turns itself down while Ruby talks (Android's audio focus: her voice asks others to duck),
 * - stops when the car's Bluetooth audio disconnects ("becoming noisy"), so it doesn't carry on
 *   from the phone's speaker,
 * - reconnects by itself if the stream drops (a Wi-Fi handoff, a dead zone), trying again a few
 *   times with growing gaps ([Reconnect]).
 */
class RadioService : MediaSessionService() {

    private var session: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val reconnect = Reconnect()

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
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) reconnect.playing()
            }

            override fun onPlayerError(error: PlaybackException) {
                val delay = reconnect.next(Reconnect.retriable(error.errorCode)) ?: run {
                    SensingLog.add("Radio: stream lost (${error.errorCodeName}); gave up after ${Reconnect.TRIES} tries.")
                    // Clear the station, so Ruby (and the player controls) show the radio as off.
                    player.stop()
                    player.clearMediaItems()
                    reconnect.playing()
                    return
                }
                SensingLog.add("Radio: stream dropped (${error.errorCodeName}); reconnecting in ${delay / 1000} s.")
                handler.postDelayed({
                    // Only if nobody stopped it meanwhile.
                    if (player.mediaItemCount > 0) {
                        player.prepare()
                        player.play()
                    }
                }, delay)
            }
        })
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
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * When to try the stream again after it drops: after 2, 4, 8, 15 and 30 seconds, then give up.
 * Playing again resets it. Kept free of Android, for tests.
 */
class Reconnect {
    private var tries = 0

    /** The delay before the next try, or null to give up (or if [retriable] is false). */
    fun next(retriable: Boolean): Long? {
        if (!retriable || tries >= TRIES) return null
        return DELAYS_MS[tries++]
    }

    fun playing() {
        tries = 0
    }

    companion object {
        val DELAYS_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)
        val TRIES = DELAYS_MS.size

        /** Network and stream errors are worth retrying; a bad station address isn't. */
        fun retriable(code: Int) = code in 2000..2999 || code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            code == PlaybackException.ERROR_CODE_TIMEOUT || code == PlaybackException.ERROR_CODE_UNSPECIFIED
    }
}
