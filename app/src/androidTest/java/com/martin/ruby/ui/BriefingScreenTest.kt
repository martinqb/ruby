package com.martin.ruby.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.martin.ruby.briefing.BriefingModule
import com.martin.ruby.briefing.BriefingProfile
import com.martin.ruby.briefing.BriefingState
import com.martin.ruby.briefing.Interest
import com.martin.ruby.ui.theme.RubyTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Step 1: the briefing's settings screen. */
@RunWith(AndroidJUnit4::class)
class BriefingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var state by mutableStateOf(BriefingState())
    private var setup by mutableStateOf(BriefingSetup(hasCar = true, hasHome = true, hasCalendar = false))
    private var calendarAsked = 0
    private var heard = 0

    private fun show() = compose.setContent {
        RubyTheme {
            BriefingScreen(
                state = state,
                setup = setup,
                actions = BriefingActions(
                    onEnable = { state = state.copy(enabled = it) },
                    onProfile = { state = state.copy(profile = it) },
                    onHearNow = { heard++ },
                    onAllowCalendar = { calendarAsked++ },
                ),
            )
        }
    }

    @Test
    fun aNewProfile_showsTheDefaultSegments() {
        show()
        compose.onNodeWithTag("briefing-switch").assertIsOff()
        compose.onNodeWithTag("module-greeting").assertIsOn()
        compose.onNodeWithTag("module-glance").assertIsOn()
        compose.onNodeWithTag("module-interest").assertIsOff()
        compose.onNodeWithTag("module-music").assertIsOn()
        compose.onNodeWithTag("module-headsup").assertIsOn()
        compose.onNodeWithTag("module-signoff").assertIsOn()
    }

    @Test
    fun turningItOn_asksForTheCalendar_once() {
        show()
        compose.onNodeWithTag("briefing-switch").performClick()
        assertTrue(state.enabled)
        assertEquals(1, calendarAsked)
        // Allowed: no more asking.
        setup = setup.copy(hasCalendar = true)
        compose.onNodeWithTag("briefing-switch").performClick()
        compose.onNodeWithTag("briefing-switch").performClick()
        assertEquals(1, calendarAsked)
    }

    @Test
    fun theInterest_turnsOnWithTonightsSky() {
        show()
        compose.onNodeWithTag("module-interest").performClick()
        assertEquals(Interest.SKY_TONIGHT, state.profile.interest)
        assertTrue(state.profile.has(BriefingModule.INTEREST))
        compose.onNodeWithText("Tonight's sky").assertIsDisplayed()
        // Off again: the choice is kept for next time.
        compose.onNodeWithTag("module-interest").performClick()
        assertTrue(BriefingModule.INTEREST !in state.profile.modules)
        assertEquals(Interest.SKY_TONIGHT, state.profile.interest)
    }

    @Test
    fun segmentsTurnOff_andTheLengthFollows() {
        show()
        compose.onNodeWithText("About 154 words, roughly 51 seconds.").assertExists()
        compose.onNodeWithTag("module-music").performClick()
        assertEquals(BriefingProfile().with(BriefingModule.MUSIC, false), state.profile)
        compose.onNodeWithText("About 119 words, roughly 39 seconds.").assertExists()
    }

    @Test
    fun hearItNow_andTheLastBriefing() {
        state = state.copy(lastText = "Good morning! It's 31 degrees.", lastMillis = 1_700_000_000_000, lastNote = "Written by Claude in 4.1 s.")
        show()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Hear it now"))
        compose.onNodeWithText("Hear it now").performClick()
        assertEquals(1, heard)
        setup = setup.copy(working = true)
        compose.onNodeWithText("Putting it together…").assertIsNotEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Good morning! It's 31 degrees."))
        compose.onNodeWithText("Written by Claude in 4.1 s.").assertExists()
    }
}
