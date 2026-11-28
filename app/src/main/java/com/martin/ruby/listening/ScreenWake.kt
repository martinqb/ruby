package com.martin.ruby.listening

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.log.SensingLog
import com.martin.ruby.ui.WakeActivity
import com.martin.ruby.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The last resort for the wake word, when Ruby may neither open her view from the background nor
 * use a full-screen notification: she turns the screen on herself (it stays locked) and keeps it on
 * while the conversation goes on, so it never stays black while she talks. Her notification shows
 * on the lock screen; tapping it opens her view. After the conversation the screen times out as usual.
 */
@Suppress("DEPRECATION") // a screen wake lock is the only way to turn the screen on without a window
object ScreenWake {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lock: PowerManager.WakeLock? = null
    private var watch: Job? = null

    /** Whether the screen is held on right now (for tests). */
    val holding: Boolean get() = lock?.isHeld == true

    /** Turns the screen on and keeps it on while she listens, thinks or talks (and [WakeScreen.GRACE_MS] after). */
    fun whileTalking(context: Context) {
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        if (lock?.isHeld != true) {
            lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Ruby:wakeWord",
            ).apply { acquire(MAX_MS) }
        }
        watch?.cancel()
        watch = scope.launch {
            delay(CHECK_MS)
            if (!power.isInteractive) SensingLog.add("Listening: Android didn't let Ruby turn the screen on.")
            var idleSince = 0L
            val started = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - started < MAX_MS) {
                val active = WakeActivity.conversationActive(ChatSession.ui.value, VoiceSession.ui.value)
                val now = SystemClock.elapsedRealtime()
                if (active) idleSince = 0L else if (idleSince == 0L) idleSince = now
                if (!WakeScreen.keepScreenOn(active, if (idleSince == 0L) 0 else now - idleSince)) break
                delay(POLL_MS)
            }
            release()
        }
    }

    /** After a full-screen notification: if the screen is still dark a moment later, wake it anyway. */
    fun ifStillDark(context: Context) {
        val app = context.applicationContext
        scope.launch {
            delay(CHECK_MS)
            if (!app.getSystemService(PowerManager::class.java).isInteractive && !WakeScreen.viewShown) {
                SensingLog.add("Listening: the full-screen notification didn't turn the screen on; turning it on.")
                whileTalking(app)
            }
        }
    }

    /** Lets go: the screen times out as usual from now (ON_AFTER_RELEASE gives the normal few seconds first). */
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }

    private const val CHECK_MS = 1_500L
    private const val POLL_MS = 500L
    /** At most this long, whatever happens (a stuck conversation mustn't keep the screen on for good). */
    private const val MAX_MS = 5 * 60_000L
}
