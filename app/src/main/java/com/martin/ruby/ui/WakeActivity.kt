package com.martin.ruby.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.martin.ruby.MainActivity
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.chat.ChatUi
import com.martin.ruby.listening.WakeScreen
import com.martin.ruby.log.SensingLog
import com.martin.ruby.ui.orb.AuroraOrb
import com.martin.ruby.ui.orb.orbStateFor
import com.martin.ruby.ui.theme.RubyTheme
import com.martin.ruby.voice.VoiceSession
import com.martin.ruby.voice.VoiceUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Ruby's small view after the wake word: just her orb and the conversation, shown over the lock
 * screen with the screen turned on. It never unlocks the phone: tapping the orb asks the user to
 * unlock (fingerprint, PIN...) and only then opens her full screen; if they cancel, the orb stays.
 * The screen is kept on while the conversation goes on, then times out as usual. When the screen
 * goes off, the view closes, so the next wake word starts fresh.
 */
class WakeActivity : ComponentActivity() {

    private var note by mutableStateOf<String?>(null)

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        WakeScreen.clearNotification(this)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        keepScreenOnWhileTalking()
        setContent {
            val chat by ChatSession.ui.collectAsState()
            val voice by VoiceSession.ui.collectAsState()
            RubyTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    WakeView(chat, voice, note, onOrb = ::openFull)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WakeScreen.viewShown = true
    }

    override fun onPause() {
        WakeScreen.viewShown = false
        super.onPause()
    }

    override fun onDestroy() {
        WakeScreen.viewShown = false
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    /** Keeps the screen on while she's listening, thinking or talking (and a moment after); then it times out as usual. */
    private fun keepScreenOnWhileTalking() {
        lifecycleScope.launch {
            var idleSince = 0L
            combine(ChatSession.ui, VoiceSession.ui) { chat, voice -> conversationActive(chat, voice) }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        idleSince = SystemClock.elapsedRealtime()
                        val since = idleSince
                        launch {
                            delay(WakeScreen.GRACE_MS)
                            if (since == idleSince && !WakeScreen.keepScreenOn(false, SystemClock.elapsedRealtime() - since)) {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    }
                }
        }
    }

    /** Tapping the orb: her full screen, after the user unlocks if the phone is locked. */
    private fun openFull() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            showFull()
            return
        }
        note = null
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = showFull()

            override fun onDismissCancelled() {
                note = "Still locked. Tap the orb to unlock and open Ruby."
            }

            override fun onDismissError() {
                SensingLog.add("Listening: couldn't ask to unlock.")
                note = "Unlock your phone to open Ruby."
            }
        })
    }

    private fun showFull() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    companion object {
        /** The conversation is going: she's listening, thinking or talking. */
        fun conversationActive(chat: ChatUi, voice: VoiceUi) =
            voice.listening || voice.speaking || voice.readingReply || chat.sending
    }
}

const val WAKE_ORB_TAG = "wake-orb"

/** The view itself: the orb, what it's doing in words, and the last few turns of the conversation. */
@Composable
fun WakeView(chat: ChatUi, voice: VoiceUi, note: String?, onOrb: () -> Unit) {
    val shown = orbStateFor(chat, voice)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuroraOrb(
            state = shown,
            level = VoiceSession::liveLevel,
            theme = voice.prefs.orbTheme,
            modifier = Modifier
                .testTag(WAKE_ORB_TAG)
                .clickable(role = Role.Button, onClickLabel = "Open Ruby") { onOrb() },
        )
        Text(shown.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val heard = voice.heard.takeIf { voice.listening && it.isNotBlank() }
        val replying = chat.replying?.ifEmpty { "…" }
        for (turn in chat.turns.takeLast(TURNS_SHOWN)) {
            Turn(if (turn.fromUser) "You" else "Ruby", turn.text)
        }
        replying?.let { Turn("Ruby", it) }
        heard?.let { Turn("You", it) }
        Spacer(Modifier.height(16.dp))
        Text(
            note ?: "Tap the orb to open Ruby.",
            style = MaterialTheme.typography.bodySmall,
            color = if (note != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("wake-note"),
        )
    }
}

@Composable
private fun Turn(who: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("wake-turn")) {
        Text(who, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 6)
    }
}

private const val TURNS_SHOWN = 4
