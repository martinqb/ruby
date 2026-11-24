package com.martin.ruby.radio

import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.ui.RadioActions
import com.martin.ruby.ui.RadioDialog
import com.martin.ruby.ui.theme.RubyTheme
import com.martin.ruby.voice.KokoroModel
import com.martin.ruby.voice.VoiceSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The radio on Android: playing (a generated tone stands in for a station, since the emulator
 * here can't reach the streams), the car's pause button, Ruby talking over it, the chat
 * commands, the iHeartRadio fallback and the Radio dialog. Plays sound: emulator only.
 */
@RunWith(AndroidJUnit4::class)
class RadioDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun log(message: String) = Log.i("RadioTest", message)

    private fun emulatorOnly() = assumeTrue(
        "Plays sound: emulator only",
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
    )

    @After
    fun quiet() {
        instrumentation.runOnMainSync {
            Radio.stop(context)
            VoiceSession.stopSpeaking()
        }
    }

    /** A minute of a quiet tone, as a WAV file the player can stream like a station. */
    private fun tone(): Uri {
        val file = File(context.cacheDir, "radio-test-tone.wav")
        if (!file.exists()) {
            val rate = 16_000
            val samples = rate * 60
            val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
            data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
            data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            data.put("data".toByteArray()).putInt(samples * 2)
            for (i in 0 until samples) data.putShort((2000 * sin(2 * PI * 440 * i / rate)).toInt().toShort())
            file.writeBytes(data.array())
        }
        return Uri.fromFile(file)
    }

    private fun startTone() {
        instrumentation.runOnMainSync { Radio.playUri(context, tone(), Stations.WGRR) }
        assertTrue("The radio should play", waitUntil(10_000) { Radio.state.value.playing })
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun itPlays_andTheCarsPauseButtonStopsIt() {
        emulatorOnly()
        startTone()
        assertEquals(Stations.WGRR, Radio.state.value.station)
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        assertTrue("Pause should stop it", waitUntil(3_000) { !Radio.state.value.playing })
    }

    @Test
    fun rubyTalksOverIt_andItKeepsGoing() {
        emulatorOnly()
        assumeTrue("Needs the Kokoro voice installed", KokoroModel.isInstalled(context))
        instrumentation.runOnMainSync { VoiceSession.start(context) }
        assertTrue(waitUntil(10_000) { VoiceSession.ui.value.usingEngine != null })
        startTone()
        instrumentation.runOnMainSync { VoiceSession.play("Good morning. Here's your briefing for today.") }
        assertTrue("She should talk", waitUntil(20_000) { VoiceSession.ui.value.sounding })
        // Turned down (by audio focus), not paused: still playing while she talks...
        assertTrue("Still playing while she talks", Radio.state.value.playing)
        assertTrue("She should finish", waitUntil(30_000) { !VoiceSession.ui.value.speaking })
        // ...and after.
        Thread.sleep(500)
        assertTrue("Still playing after she's done", Radio.state.value.playing)
        log("Radio kept playing through Ruby talking")
    }

    @Test
    fun theChatCommands() {
        emulatorOnly()
        instrumentation.runOnMainSync {
            ChatSession.newConversation()
            ChatSession.send(context, "Turn off the radio")
        }
        assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
        assertEquals("The radio isn't on.", ChatSession.ui.value.turns.last().text)

        instrumentation.runOnMainSync { ChatSession.send(context, "Play the radio") }
        assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
        assertEquals("Playing WGRR 103.5.", ChatSession.ui.value.turns.last().text)
        assertEquals(Stations.WGRR, Radio.state.value.station)
        assertTrue(Radio.state.value.on || Radio.state.value.problem != null)

        instrumentation.runOnMainSync { ChatSession.send(context, "Stop") }
        assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
        assertEquals("Radio off.", ChatSession.ui.value.turns.last().text)
        assertFalse(Radio.state.value.on)
        instrumentation.runOnMainSync { ChatSession.newConversation() }
    }

    /** Another app's audio that ignores pause (stands in for iHeartRadio when it doesn't respond). */
    private fun otherAppPlaying(): android.media.MediaPlayer = android.media.MediaPlayer().apply {
        setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
        setDataSource(context, tone())
        isLooping = true
        prepare()
        start()
    }

    @Test
    fun stopSaid_whileIHeartPlays_isTrueToWhatHappened() {
        emulatorOnly()
        instrumentation.runOnMainSync { Radio.pretendOpenedInIHeart(Stations.WEBN) }
        try {
            // Nothing playing at all: says so, doesn't claim a pause.
            assertEquals("WEBN 102.7 isn't playing right now.", kotlinx.coroutines.runBlocking { Radio.stopSaid(context) })
            // Playing, and the pause doesn't take: says where it's playing and how to stop it.
            val other = otherAppPlaying()
            try {
                assertTrue(waitUntil(3_000) { Radio.anythingPlaying(context) })
                assertEquals(
                    "WEBN 102.7 is playing in iHeartRadio. Pause it there or with your steering wheel.",
                    kotlinx.coroutines.runBlocking { Radio.stopSaid(context) },
                )
                // And "stop" in the chat goes to the phone, not the AI.
                instrumentation.runOnMainSync {
                    ChatSession.newConversation()
                    ChatSession.send(context, "stop")
                }
                assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
                assertEquals(
                    "WEBN 102.7 is playing in iHeartRadio. Pause it there or with your steering wheel.",
                    ChatSession.ui.value.turns.last().text,
                )
            } finally {
                other.release()
            }
        } finally {
            instrumentation.runOnMainSync {
                Radio.pretendOpenedInIHeart(null)
                ChatSession.newConversation()
            }
        }
    }

    @Test
    fun theAI_isToldWhatTheRadioDid() {
        emulatorOnly()
        instrumentation.runOnMainSync {
            ChatSession.newConversation()
            ChatSession.send(context, "Play the radio")
        }
        assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
        val note = Radio.note().orEmpty()
        assertTrue(note, note.contains("played WGRR 103.5 in Ruby"))
        // The next message, to whichever AI, carries the note.
        instrumentation.runOnMainSync { ChatSession.send(context, "Is the radio on?") }
        assertTrue(waitUntil(10_000) { !ChatSession.ui.value.sending })
        val sent = ChatSession.ui.value.turns.last { it.fromUser }.sent.orEmpty()
        assertTrue(sent, sent.contains("[Radio (done by the phone; this really happened):") && sent.contains("played WGRR 103.5 in Ruby"))
        instrumentation.runOnMainSync {
            ChatSession.dismissError()
            ChatSession.newConversation()
        }
    }

    @Test
    fun webn_opensInIHeartRadio_orSaysItIsNotInstalled() {
        emulatorOnly()
        val installed = context.packageManager.getLaunchIntentForPackage(Radio.IHEART_PACKAGE) != null
        assumeTrue("The emulator has no iHeartRadio", !installed)
        var said = ""
        instrumentation.runOnMainSync { said = Radio.play(context, Stations.WEBN) }
        assertEquals("WEBN 102.7 plays in the iHeartRadio app, which isn't installed.", said)
    }

    @Test
    fun theRadioDialog() {
        var played: Station? = null
        var afterBriefing = false
        compose.setContent {
            RubyTheme {
                RadioDialog(
                    state = Radio.State(station = Stations.WGRR, playing = true),
                    afterBriefing = afterBriefing,
                    actions = RadioActions(onPlay = { played = it }, onAfterBriefing = { afterBriefing = it }),
                    onClose = {},
                )
            }
        }
        compose.onNodeWithText("Playing WGRR 103.5").assertIsDisplayed()
        compose.onNodeWithTag("radio-stop").assertIsDisplayed()
        compose.onNodeWithText("Cincinnati · opens in iHeartRadio").assertIsDisplayed()
        compose.onNodeWithTag("radio-play-webn").performClick()
        assertEquals(Stations.WEBN, played)
        compose.onNodeWithTag("radio-after-briefing").performClick()
        assertTrue("The after-briefing switch turns on (off by default)", afterBriefing)
    }
}
