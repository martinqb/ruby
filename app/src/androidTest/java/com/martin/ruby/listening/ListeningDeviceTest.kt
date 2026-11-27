package com.martin.ruby.listening

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.ui.VoiceSettingsActions
import com.martin.ruby.ui.VoiceSettingsScreen
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * The listening modes on an (emulated) phone: the real Vosk model, installed through the app's
 * own checks, hearing recorded speech (the wake phrase, someone talking over Ruby, and other
 * talk), the mic and notification starting and stopping, and the Voice settings page.
 */
@RunWith(AndroidJUnit4::class)
class ListeningDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun isEmulator() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")

    private fun log(message: String) = Log.i("ListeningDeviceTest", message)

    /** The model, installed from the copy the test put in the cache (checked like a download). */
    private fun installedModel(): Model = runBlocking {
        if (!VoskModel.isInstalled(context)) {
            val local = File(context.cacheDir, "vosk-model.zip")
            assumeTrue("Copy the model zip into the app's cache to run this", local.exists())
            VoskModel.install(context, archive = local) {}
        }
        assertTrue(VoskModel.isInstalled(context))
        Model(VoskModel.dir(context).absolutePath)
    }

    /** A recording from the test's assets (16 kHz mono WAV), as samples. */
    private fun recording(name: String): ShortArray {
        val bytes = instrumentation.context.assets.open("listening/$name").use { it.readBytes() }
        val data = java.nio.ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        // A second of quiet after it, as if the speaker paused.
        return ShortArray(data.remaining() + 16_000).also { data.get(it, 0, data.remaining()) }
    }

    /** Everything [recognizer] hears in [samples], fed 100 ms at a time like the mic. */
    private fun hear(recognizer: Recognizer, samples: ShortArray): List<String> {
        val heard = mutableListOf<String>()
        var i = 0
        while (i < samples.size) {
            val n = minOf(1_600, samples.size - i)
            val final = recognizer.acceptWaveForm(samples.copyOfRange(i, i + n), n)
            val json = JSONObject(if (final) recognizer.result else recognizer.partialResult)
            json.optString(if (final) "text" else "partial").takeIf { it.isNotBlank() }?.let { heard += it }
            i += n
        }
        JSONObject(recognizer.finalResult).optString("text").takeIf { it.isNotBlank() }?.let { heard += it }
        return heard
    }

    @Test
    fun theModel_installsAndKnowsItsWords() = runBlocking {
        installedModel().close()
        val files = VoskModel.dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        log("Installed: ${files / 1_048_576} MB")
        val started = SystemClock.elapsedRealtime()
        assertEquals(setOf("hey", "ruby", "jarvis"), VoskModel.knownWords(context, listOf("hey", "ruby", "jarvis", "zxqvb")))
        log("Word lookup took ${SystemClock.elapsedRealtime() - started} ms")
        assertEquals(listOf("zxqvb"), Listening.unknownWords(context, "Hey Zxqvb"))
        assertEquals(emptyList<String>(), Listening.unknownWords(context, "Hey Ruby"))
    }

    @Test
    fun heyRuby_isHeard_andOtherTalkIsNot() {
        val model = installedModel()
        try {
            for ((file, wakes) in listOf("hey-ruby.wav" to true, "not-wake.wav" to false, "talk-over.wav" to false)) {
                Recognizer(model, 16_000f, WakePhrase.grammar("Hey Ruby")).use { r ->
                    val heard = hear(r, recording(file))
                    log("Wake grammar, $file: $heard")
                    assertEquals("$file: $heard", wakes, heard.any { WakePhrase.heard("Hey Ruby", it) })
                }
            }
        } finally {
            model.close()
        }
    }

    @Test
    fun talkingOverRuby_isHeardAsTheUser() {
        val model = installedModel()
        try {
            Recognizer(model, 16_000f).use { r ->
                val heard = hear(r, recording("talk-over.wav"))
                log("Talking over Ruby: $heard")
                val saying = "Your dentist appointment is on Tuesday at nine thirty in the morning."
                assertTrue("$heard", heard.any { BargeIn.isUser(it, saying) })
                // The same words coming back as her own echo don't count.
                assertFalse(heard.any { BargeIn.isUser(it, "What about Thursday instead of Tuesday?") })
            }
        } finally {
            model.close()
        }
    }

    private var saved: ListeningPrefs? = null

    @After
    fun putSettingsBack() = runBlocking {
        saved?.let { ListeningSettings(context).save(it) }
        saved = null
    }

    @Test
    fun theWakeWord_opensTheMic_withItsNotification_andTheQuickSwitchCloses_it() = runBlocking {
        assumeTrue("Uses the mic: emulator only", isEmulator())
        installedModel().close()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val settings = ListeningSettings(context)
        saved = settings.current()
        instrumentation.runOnMainSync { Listening.save(context, ListeningPrefs(wakeWord = true)) }
        assertTrue("The mic listens for the wake word", waitUntil(15_000) { Listening.ui.value.mic == MicUse.WAKE })
        assertTrue("With its notification", waitUntil(5_000) { serviceRunning() })

        instrumentation.runOnMainSync { Listening.setOn(context, false) }
        assertTrue("Off: the mic is let go", waitUntil(5_000) { Listening.ui.value.mic == MicUse.NONE })
        assertTrue("And the notification goes", waitUntil(5_000) { !serviceRunning() })
        assertTrue("The wake word stays ticked", settings.current().wakeWord)
    }

    private fun serviceRunning(): Boolean {
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys activity services ${context.packageName}/.listening.ListeningService")
            .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r -> r.readText() } }
        return out.contains("isForeground=true")
    }

    private fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun theVoiceSettingsPage_hasThreeTickBoxes_thatCombine() {
        var prefs by mutableStateOf(ListeningPrefs())
        compose.setContent {
            RubyTheme {
                VoiceSettingsScreen(
                    state = Listening.Ui(prefs = prefs, model = VoskModel.State.Installed),
                    hasMic = true,
                    actions = VoiceSettingsActions(onSave = { prefs = it }),
                )
            }
        }
        compose.onNodeWithText("The mic opens again after Ruby answers, so you can keep talking.", substring = true).assertExists()
        compose.onNodeWithText("Ruby listens for your wake word, then starts listening. Uses more battery.").assertExists()
        compose.onNodeWithText("Talk over Ruby to interrupt her, like a phone call.", substring = true).assertExists()

        compose.onNodeWithTag("mode-conversation").performClick()
        compose.onNodeWithTag("mode-wake").performClick()
        compose.onNodeWithTag("mode-duplex").performClick()
        compose.waitForIdle()
        assertEquals(ListeningPrefs(conversation = true, wakeWord = true, fullDuplex = true), prefs)

        // The wake word's text box: a bad one isn't saved, a good one is.
        compose.onNodeWithTag("wake-phrase").performTextReplacement("Yo")
        compose.onNodeWithText("Use something longer", substring = true).assertExists()
        compose.onNodeWithTag("wake-phrase").performTextReplacement("OK Jarvis")
        compose.onNodeWithTag("wake-save").performClick()
        compose.waitForIdle()
        assertEquals("OK Jarvis", prefs.wakePhrase)

        // The quick switch pauses all three and keeps them ticked.
        compose.onNodeWithTag("listening-on").assertIsOn().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("listening-on").assertIsOff()
        assertFalse(prefs.active)
        assertTrue(prefs.conversation && prefs.wakeWord && prefs.fullDuplex)
    }
}
