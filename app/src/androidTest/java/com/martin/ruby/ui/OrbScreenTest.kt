package com.martin.ruby.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.martin.ruby.log.LogEntry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.martin.ruby.chat.ChatTurn
import com.martin.ruby.chat.ChatUi
import com.martin.ruby.ui.theme.RubyTheme
import com.martin.ruby.voice.VoicePrefs
import com.martin.ruby.voice.VoiceUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The orb on the main screen: shown or hidden by its switch, and saying what it shows. (That it
 * stops when scrolled away, in the background or with the screen off is checked on the phone by
 * counting its frames.)
 */
@RunWith(AndroidJUnit4::class)
class OrbScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var voice by mutableStateOf(VoiceUi())
    private var chat by mutableStateOf(ChatUi(hasKey = true, turns = listOf(ChatTurn(fromUser = false, text = "Neptune is far away."))))

    /** Long enough to scroll: everything allowed, and a long log. */
    private val state = MainUiState(
        hasBluetoothPermission = true,
        hasActivityPermission = true,
        log = List(40) { LogEntry(1_700_000_000_000 + it * 1000L, "Log line $it") },
    )

    /** The orb animates without end, so the test's clock is moved on by hand. */
    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            RubyTheme {
                MainScreen(state = state, actions = MainActions(), ruby = RubyUi(chat = chat, voice = voice))
            }
        }
        step()
    }

    private fun step(millis: Long = 100) = compose.mainClock.advanceTimeBy(millis)

    @Test
    fun theSwitchShowsAndHidesTheOrb() {
        show()
        compose.onNodeWithTag(ORB_TAG).assertIsDisplayed()
        compose.onNodeWithText("Neptune is far away.").assertIsDisplayed() // her reply, under the orb
        voice = voice.copy(prefs = VoicePrefs(showOrb = false))
        step()
        compose.onNodeWithTag(ORB_TAG).assertDoesNotExist()
        compose.onNodeWithText("Neptune is far away.").assertIsDisplayed() // the chat stays
    }

    @Test
    fun itSaysWhatItShows() {
        show()
        compose.onNodeWithContentDescription("Ruby: Ready").assertIsDisplayed()
        voice = voice.copy(listening = true)
        step()
        compose.onNodeWithContentDescription("Ruby: Listening").assertIsDisplayed()
        voice = voice.copy(listening = false)
        chat = chat.copy(sending = true)
        step()
        compose.onNodeWithContentDescription("Ruby: Thinking").assertIsDisplayed()
        compose.onNodeWithText("Thinking").assertIsDisplayed()
        chat = chat.copy(sending = false, error = "No internet")
        step()
        compose.onNodeWithContentDescription("Ruby: Something went wrong").assertIsDisplayed()
    }

}
