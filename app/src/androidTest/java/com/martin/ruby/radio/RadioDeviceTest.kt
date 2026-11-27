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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.ui.RadioActions
import com.martin.ruby.ui.RadioScreen
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

    /** "Play the radio" needs a default station: Martin's profile, for this test (put back afterwards). */
    private fun withMartinsPresets() = kotlinx.coroutines.runBlocking {
        val store = PresetStore(context)
        savedPresets = store.current()
        store.update { Presets.MARTIN }
    }

    private var savedPresets: Presets? = null

    @After
    fun putPresetsBack() = kotlinx.coroutines.runBlocking {
        savedPresets?.let { p -> PresetStore(context).update { p } }
        savedPresets = null
    }

    @After
    fun quiet() {
        instrumentation.runOnMainSync {
            Radio.stop(context)
            VoiceSession.stopSpeaking()
        }
    }

    /** Three minutes of a quiet tone (longer than any test plays it), as a WAV file the player can stream like a station. */
    private fun tone(): Uri {
        val file = File(context.cacheDir, "radio-test-tone-3min.wav")
        if (!file.exists()) {
            val rate = 16_000
            val samples = rate * 180
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

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).let { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
        }

    @Test
    fun whilePlaying_itsAForegroundMediaService_holdingWakeAndWifiLocks() {
        emulatorOnly()
        startTone()
        Thread.sleep(1_000)
        val service = shell("dumpsys activity services com.martin.ruby/.radio.RadioService")
        log("Service: " + service.lines().filter { "isForeground" in it || "foregroundServiceType" in it }.joinToString(" | ") { it.trim() })
        assertTrue("A foreground service", service.contains("isForeground=true"))
        // Android lists the media-playback type as 0x00000002.
        assertTrue("Of the media-playback type", Regex("types=0x0*2\\b").containsMatchIn(service))
        val power = shell("dumpsys power")
        assertTrue("Holds a wake lock while playing", power.lines().any { "ExoPlayer" in it && "WAKE_LOCK" in it.uppercase() || "ExoPlayer:WakeLockManager" in it })
    }

    @Test
    fun withTheScreenOff_itKeepsPlaying() {
        emulatorOnly()
        startTone()
        shell("input keyevent KEYCODE_SLEEP")
        try {
            val minutes = 2
            repeat(minutes * 6) {
                Thread.sleep(10_000)
                assertTrue("Still playing after ${(it + 1) * 10} s with the screen off", Radio.state.value.playing)
            }
            log("Played $minutes minutes with the screen off")
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
        }
    }

    @Test
    fun aDeadStream_isRetried_thenGivenUpCleanly() {
        emulatorOnly()
        // Nothing listens on this port: every connection fails, like a lost signal.
        instrumentation.runOnMainSync { Radio.playUri(context, Uri.parse("https://127.0.0.1:9/dead.mp3"), Stations.WGRR) }
        assertTrue("Shows it's reconnecting", waitUntil(10_000) { Radio.state.value.problem?.contains("reconnecting") == true })
        assertTrue("Still counts as on while reconnecting, so stop works", Radio.state.value.on)
        log("Reconnecting: ${Radio.state.value.problem}")
        // 2 + 4 + 8 + 15 + 30 seconds of tries, then it gives up and is off.
        assertTrue("Gives up after the tries", waitUntil(90_000) { !Radio.state.value.on })
        assertEquals("WGRR 103.5 couldn't reconnect.", Radio.state.value.problem)
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
        withMartinsPresets()
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
                    "WEBN 102.7 is still playing in iHeartRadio. Want me to try pausing it again?",
                    kotlinx.coroutines.runBlocking { Radio.stopSaid(context) },
                )
                // And "stop" in the chat goes to the phone, not the AI.
                instrumentation.runOnMainSync {
                    ChatSession.newConversation()
                    ChatSession.send(context, "stop")
                }
                assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
                assertEquals(
                    "WEBN 102.7 is still playing in iHeartRadio. Want me to try pausing it again?",
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
    fun stopSaid_stopsRubysPlayerFirst_thenTheOtherApp() {
        emulatorOnly()
        startTone()
        val other = otherAppPlaying()
        try {
            assertTrue(waitUntil(3_000) { Radio.anythingPlaying(context) })
            // Ruby's player really ends; the stand-in app ignores pause, and Ruby says so.
            assertEquals(
                "Radio off. Something's still playing in another app. Want me to try pausing it again?",
                kotlinx.coroutines.runBlocking { Radio.stopSaid(context) },
            )
            assertFalse(Radio.state.value.on)
            var ended = false
            instrumentation.runOnMainSync { ended = !Radio.ownPlayerPlaying() }
            assertTrue("Ruby's own player should have ended", ended)
        } finally {
            other.release()
        }
        // With only Ruby playing, it stops and nothing else is touched.
        startTone()
        assertEquals("Radio off.", kotlinx.coroutines.runBlocking { Radio.stopSaid(context) })
        assertFalse(Radio.state.value.on)
    }

    /**
     * The car's touchscreen play and pause come over Bluetooth (AVRCP) into Android's media
     * session service, which sends them on to the playing app's media session. `cmd media_session
     * dispatch` takes that same route. And what the car's screen shows is the session's metadata.
     */
    @Test
    fun theCarScreensPauseAndPlay_andWhatItShows() {
        emulatorOnly()
        startTone()
        val sessions = shell("dumpsys media_session")
        assertTrue("Ruby's radio session is listed", sessions.contains("ruby-radio"))
        assertTrue("It shows the station", sessions.contains("WGRR 103.5"))
        assertTrue("And that it's Ruby's radio", sessions.contains("Ruby's radio"))

        shell("cmd media_session dispatch pause")
        assertTrue("The car's pause stops it", waitUntil(3_000) { !Radio.state.value.playing })
        assertFalse(Radio.state.value.on)
        shell("cmd media_session dispatch play")
        assertTrue("The car's play starts it again", waitUntil(5_000) { Radio.state.value.playing })
        shell("cmd media_session dispatch play-pause")
        assertTrue("The car's play/pause toggle stops it", waitUntil(3_000) { !Radio.state.value.playing })
    }

    @Test
    fun webnsButton_goesBackToPlay_whenIHeartRadioStops() {
        emulatorOnly()
        instrumentation.runOnMainSync { Radio.pretendOpenedInIHeart(Stations.WEBN, context) }
        try {
            assertEquals("Play", Radio.state.value.buttonLabel(Stations.WEBN))
            // iHeartRadio starts playing (a stand-in app's audio): Open.
            val other = otherAppPlaying()
            try {
                assertTrue("Open while it plays", waitUntil(3_000) { Radio.state.value.buttonLabel(Stations.WEBN) == "Open" })
            } finally {
                other.release() // iHeartRadio closed
            }
            assertTrue("Back to Play once it stops", waitUntil(3_000) { Radio.state.value.buttonLabel(Stations.WEBN) == "Play" })
            // And looking again when the radio screen comes back keeps it Play.
            instrumentation.runOnMainSync { Radio.refreshOtherApps(context) }
            assertEquals("Play", Radio.state.value.buttonLabel(Stations.WEBN))
        } finally {
            instrumentation.runOnMainSync { Radio.pretendOpenedInIHeart(null) }
        }
    }

    @Test
    fun whenThePauseDoesntTake_rubyOffersToTryAgain_andYesTries() {
        emulatorOnly()
        val other = otherAppPlaying()
        try {
            assertTrue(waitUntil(3_000) { Radio.anythingPlaying(context) })
            instrumentation.runOnMainSync {
                ChatSession.newConversation()
                ChatSession.send(context, "stop")
            }
            assertTrue(waitUntil(8_000) { !ChatSession.ui.value.sending })
            assertEquals("Something's still playing in another app. Want me to try pausing it again?", ChatSession.ui.value.turns.last().text)
            assertTrue(Radio.retryOffered())
            // "Yes" goes to the phone, which tries again (and again is honest that it didn't take).
            instrumentation.runOnMainSync { ChatSession.send(context, "Yes") }
            assertTrue(waitUntil(8_000) { !ChatSession.ui.value.sending })
            assertEquals("Something's still playing in another app. Want me to try pausing it again?", ChatSession.ui.value.turns.last().text)
            // Once it has stopped, a "yes" is no longer for the radio.
            other.pause()
            assertTrue(waitUntil(3_000) { !Radio.anythingPlaying(context) })
            instrumentation.runOnMainSync { ChatSession.send(context, "stop the radio") }
            assertTrue(waitUntil(8_000) { !ChatSession.ui.value.sending })
            assertEquals("The radio isn't on.", ChatSession.ui.value.turns.last().text)
            assertFalse(Radio.retryOffered())
        } finally {
            other.release()
            instrumentation.runOnMainSync { ChatSession.newConversation() }
        }
    }

    @Test
    fun theAI_isToldWhatTheRadioDid() {
        emulatorOnly()
        withMartinsPresets()
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

    private fun screen(presets: () -> Presets, onPresets: ((Presets) -> Presets) -> Unit, played: (Station) -> Unit = {}) =
        compose.setContent {
            RubyTheme {
                RadioScreen(
                    state = Radio.State(),
                    presets = presets(),
                    afterBriefing = false,
                    actions = RadioActions(
                        onPlay = played,
                        onPresets = onPresets,
                        search = { _, text ->
                            // Stands in for Radio Browser.
                            listOf(
                                Station("rb:jazz", "WNKU Jazz 89.7", "Kentucky, USA", "https://example.com/jazz.mp3", null, listOf("WNKU Jazz 89.7"), "jazz"),
                                Station("rb:webn", "102.7 WEBN", "Ohio, USA", null, 1701),
                            ).filter { text.isNotBlank() }
                        },
                    ),
                )
            }
        }

    @Test
    fun theRadioScreen_newUser_findsSavesAndPlays() {
        var presets by androidx.compose.runtime.mutableStateOf(Presets())
        var played: Station? = null
        screen({ presets }, { change -> presets = change(presets) }, { played = it })
        compose.onNodeWithText("No stations saved yet. Tap Find a station to add some.").assertIsDisplayed()
        compose.onNodeWithTag("find-station").performClick()
        compose.onNodeWithTag("search-field").performTextInput("jazz")
        compose.onNodeWithTag("search-go").performClick()
        compose.waitUntil(3_000) { compose.onAllNodesWithTag("result-rb:jazz").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Kentucky, USA · jazz · plays in Ruby").assertIsDisplayed()
        compose.onNodeWithText("Ohio, USA · opens in iHeartRadio").assertIsDisplayed()
        compose.onNodeWithTag("save-rb:jazz").performClick()
        assertEquals(listOf("rb:jazz"), presets.list.map { it.station.id })
        assertEquals("rb:jazz", presets.default?.station?.id) // the first saved is the default
        compose.onNodeWithTag("play-rb:jazz").performClick()
        assertEquals("rb:jazz", played?.id)
    }

    @Test
    fun theRadioScreen_renameReorderDefaultRemove() {
        var presets by androidx.compose.runtime.mutableStateOf(Presets.MARTIN)
        screen({ presets }, { change -> presets = change(presets) })
        compose.onNodeWithText("WGRR 103.5 (default)").assertIsDisplayed()
        // Rename WEBN.
        compose.onNodeWithTag("more-webn").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("rename-field").performTextClearance()
        compose.onNodeWithTag("rename-field").performTextInput("The Rock")
        compose.onNodeWithText("Save").performClick()
        assertEquals("The Rock", presets.list.first { it.station.id == "webn" }.label)
        // Move it up and make it the default.
        compose.onNodeWithTag("more-webn").performClick()
        compose.onNodeWithText("Move up").performClick()
        assertEquals(listOf("webn", "wgrr"), presets.list.map { it.station.id })
        compose.onNodeWithTag("more-webn").performClick()
        compose.onNodeWithText("Make default").performClick()
        compose.onNodeWithText("The Rock (default)").assertIsDisplayed()
        // Remove it: WGRR is the default again.
        compose.onNodeWithTag("more-webn").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("wgrr"), presets.list.map { it.station.id })
        assertEquals("wgrr", presets.default?.station?.id)
    }

    @Test
    fun presets_areSavedOnThePhone() = kotlinx.coroutines.runBlocking {
        emulatorOnly()
        val store = PresetStore(context)
        val before = store.current()
        try {
            store.update { Presets().add(Stations.WEBN).add(Stations.WGRR).makeDefault("wgrr") }
            val read = PresetStore(context).current()
            assertEquals(listOf("webn", "wgrr"), read.list.map { it.station.id })
            assertEquals("wgrr", read.default?.station?.id)
        } finally {
            store.update { before }
        }
    }

    @Test
    fun playAPreset_byItsName_withTheSameControls() = kotlinx.coroutines.runBlocking {
        emulatorOnly()
        val store = PresetStore(context)
        val before = store.current()
        // A saved station named "Test tone", streaming the test tone.
        val toneStation = Station("rb:tone", "Tone FM", "Test", tone().toString(), null, listOf("Tone FM"))
        try {
            store.update { Presets().add(toneStation).rename("rb:tone", "Test tone") }
            instrumentation.runOnMainSync {
                ChatSession.newConversation()
                ChatSession.send(context, "Play test tone")
            }
            assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
            assertEquals("Playing Tone FM.", ChatSession.ui.value.turns.last().text)
            assertTrue("It plays through the same player", waitUntil(10_000) { Radio.state.value.playing })
            // ...so the car's pause button stops it, as for any station.
            val audio = context.getSystemService(AudioManager::class.java)
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now, now + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            assertTrue(waitUntil(3_000) { !Radio.state.value.playing })
        } finally {
            store.update { before }
            instrumentation.runOnMainSync { ChatSession.newConversation() }
        }
    }

    @Test
    fun noPresets_playTheRadio_saysToFindOne() = kotlinx.coroutines.runBlocking {
        emulatorOnly()
        val store = PresetStore(context)
        val before = store.current()
        try {
            store.update { Presets() }
            instrumentation.runOnMainSync {
                ChatSession.newConversation()
                ChatSession.send(context, "Play the radio")
            }
            assertTrue(waitUntil(5_000) { !ChatSession.ui.value.sending })
            assertEquals(RadioCommands.NO_STATIONS, ChatSession.ui.value.turns.last().text)
        } finally {
            store.update { before }
            instrumentation.runOnMainSync { ChatSession.newConversation() }
        }
    }

}
