package com.martin.ruby.radio

import android.content.ComponentName
import android.content.Context
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Presses play on iHeartRadio's own player, through the media browser service it publishes for
 * Android Auto and other remotes. Unlike a media key, which Android sends to whichever app played
 * last (often Ruby's own radio), this reaches iHeartRadio itself. iHeartRadio may refuse apps it
 * doesn't know; then [pressPlay] says so and Ruby falls back to the media key.
 */
object IHeartRemote {

    private val SERVICE = ComponentName(Radio.IHEART_PACKAGE, "com.clearchannel.iheartradio.remote.mbs.service.RadioService")

    /** True if iHeartRadio let Ruby connect and the play was sent. Main thread. */
    suspend fun pressPlay(context: Context, timeoutMs: Long = CONNECT_MS): Boolean {
        val app = context.applicationContext
        var browser: MediaBrowser? = null
        try {
            val token = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<MediaSession.Token?> { cont ->
                    val callback = object : MediaBrowser.ConnectionCallback() {
                        override fun onConnected() {
                            if (cont.isActive) cont.resume(browser?.sessionToken)
                        }

                        override fun onConnectionFailed() {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onConnectionSuspended() {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    browser = MediaBrowser(app, SERVICE, callback, null).also { it.connect() }
                }
            }
            if (token == null) {
                SensingLog.add("Radio: iHeartRadio didn't let Ruby reach its player.")
                return false
            }
            val player = MediaController(app, token)
            SensingLog.add("Radio: iHeartRadio's player has \"${player.metadata?.description?.title ?: "nothing"}\"; pressing play.")
            player.transportControls.play()
            return true
        } catch (e: Exception) {
            // Not installed, not visible to Ruby, or the service misbehaved.
            SensingLog.add("Radio: couldn't reach iHeartRadio's player (${e.javaClass.simpleName}).")
            return false
        } finally {
            try {
                browser?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private const val CONNECT_MS = 3_000L
}
