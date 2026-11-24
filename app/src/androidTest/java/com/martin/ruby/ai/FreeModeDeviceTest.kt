package com.martin.ruby.ai

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.chat.ChatUi
import com.martin.ruby.reminders.Reminder
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.ui.AiEngineDialogContent
import com.martin.ruby.ui.ChatActions
import com.martin.ruby.ui.theme.RubyTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Free mode on Android: the AI dialog, reminders done by the phone (with Gemini Nano's
 * answers stood in for), and the chat when Free mode isn't there. The reminder and chat
 * tests change data, so they run on the emulator only (which has no Gemini Nano).
 */
@RunWith(AndroidJUnit4::class)
class FreeModeDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun emulatorOnly() = assumeTrue(
        "Changes reminders and settings: emulator only",
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
    )

    // The AI dialog

    private var chosen: AiEngine? = null
    private var downloads = 0

    private fun dialog(status: FreeMode.Status, chat: ChatUi = ChatUi(hasKey = true)) = compose.setContent {
        RubyTheme {
            AiEngineDialogContent(
                chat, status,
                ChatActions(onChooseEngine = { chosen = it }, onDownloadFree = { downloads++ }),
                onClose = {},
            )
        }
    }

    @Test
    fun theSwitch_showsTheHelperLine_andChooses() {
        dialog(FreeMode.Status.Ready)
        compose.onNodeWithText(AiEngine.HELPER).assertIsDisplayed()
        compose.onNodeWithText("Ready on this phone.").assertIsDisplayed()
        compose.onNodeWithTag("engine-FREE").performClick()
        assertEquals(AiEngine.FREE, chosen)
    }

    @Test
    fun onPhonesWithoutGeminiNano_FreeModeCantBeChosen() {
        dialog(FreeMode.Status.NotOnThisPhone)
        compose.onNodeWithTag("engine-helper").assertIsDisplayed()
        compose.onNodeWithText(AiEngine.HELPER).assertDoesNotExist()
        compose.onNodeWithTag("engine-FREE").assertIsNotEnabled()
    }

    @Test
    fun notDownloadedYet_offersTheDownload() {
        dialog(FreeMode.Status.Downloadable, ChatUi(hasKey = false))
        compose.onNodeWithText("Download Free mode").performClick()
        assertEquals(1, downloads)
        compose.onNodeWithText("Needs your Anthropic API key: add it on the chat screen.").assertIsDisplayed()
    }

    // Reminders in Free mode, with Nano's answers stood in for

    private fun runner(answer: String) = FreeReminderRunner(context) { _, _ -> answer }

    @Test
    fun aSimpleReminder_isSetByThePhone_andCanBeCancelled() = runBlocking {
        emulatorOnly()
        val store = ReminderStore(context)
        val at = LocalDateTime.now().plusHours(3).withSecond(0).withNano(0)
        val set = runner("""```json
{"text": "Test call Mom (Free mode)", "at": "$at", "place": null, "sure": true}
```""").handle(FreeReminders.Kind.SET, "Remind me to call Mom in three hours")
        try {
            assertNull(set.couldNot)
            assertTrue(set.reply, set.reply.startsWith("Done. I'll remind you to test call Mom (Free mode)"))
            val made = store.pending().single { it.text == "Test call Mom (Free mode)" }
            // Asking again doesn't make a second one.
            val again = runner("""{"text": "Test call Mom (Free mode)", "at": "$at", "place": null}""")
                .handle(FreeReminders.Kind.SET, "Remind me to call Mom in three hours")
            assertTrue(again.reply, again.reply.startsWith("That one's already set"))
            assertEquals(1, store.pending().count { it.text == "Test call Mom (Free mode)" })
            // Listed without any AI.
            assertTrue(runner("").handle(FreeReminders.Kind.LIST, "What reminders do I have?").reply.contains("Test call Mom (Free mode)"))
            // Cancelled.
            val cancel = runner("""{"id": ${made.id}}""").handle(FreeReminders.Kind.CANCEL, "Cancel the Mom reminder")
            assertEquals("Reminder cancelled: Test call Mom (Free mode)", cancel.action)
            assertEquals(Reminder.STATE_CANCELLED, store.get(made.id)!!.state)
        } finally {
            store.pending().filter { it.text == "Test call Mom (Free mode)" }.forEach { store.cancel(it.id) }
        }
    }

    @Test
    fun whatTheChecksCatch() = runBlocking {
        emulatorOnly()
        // Unclear, in the past, or a place that isn't saved: nothing is set.
        assertEquals("the time or place wasn't clear enough.",
            runner("""{"text":"Something","at":null,"place":null,"sure":false}""").handle(FreeReminders.Kind.SET, "Remind me later").couldNot)
        val past = LocalDateTime.now().minusHours(2).withSecond(0).withNano(0)
        assertTrue(runner("""{"text":"Too late","at":"$past","place":null}""").handle(FreeReminders.Kind.SET, "x").couldNot!!.contains("in the past"))
        assertEquals("\"Narnia\" isn't one of your saved places.",
            runner("""{"text":"Find the lamp post","at":null,"place":"Narnia"}""").handle(FreeReminders.Kind.SET, "x").couldNot)
        assertEquals("I couldn't make sense of that request.",
            runner("Sure, I'll remind you!").handle(FreeReminders.Kind.SET, "x").couldNot)
        assertTrue(ReminderStore(context).pending().none { it.text in setOf("Something", "Too late", "Find the lamp post") })
    }

    // The chat when Free mode isn't on the phone (the emulator has no Gemini Nano)

    @Test
    fun freeModeUnavailable_andNoKey_saysSo() = runBlocking {
        emulatorOnly()
        val settings = AiSettings(context)
        val before = settings.current()
        val keys = com.martin.ruby.chat.ApiKeyStore(context)
        assumeTrue("Needs no Claude key on the emulator", !keys.hasKey())
        try {
            settings.save(AiEngine.FREE)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ChatSession.checkKey(context)
                ChatSession.newConversation()
            }
            waitFor { ChatSession.ui.value.engine == AiEngine.FREE }
            assertTrue("Free mode chats without a key", ChatSession.ui.value.canChat)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ChatSession.send(context, "Hello in Free mode") }
            waitFor { !ChatSession.ui.value.sending && ChatSession.ui.value.error != null }
            assertEquals(
                "Free mode isn't available on this phone right now, and there's no Claude key to fall back on.",
                ChatSession.ui.value.error,
            )
        } finally {
            settings.save(before)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ChatSession.dismissError()
                ChatSession.newConversation()
            }
        }
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            kotlinx.coroutines.delay(50)
        }
        assertNotNull("Timed out", null.takeIf { !condition() } ?: Unit)
    }
}
