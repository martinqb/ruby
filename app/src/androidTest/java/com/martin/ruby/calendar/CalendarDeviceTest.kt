package com.martin.ruby.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.chat.ChatUi
import com.martin.ruby.ui.ChatActions
import com.martin.ruby.ui.ChatInput
import com.martin.ruby.ui.theme.RubyTheme
import com.martin.ruby.voice.VoiceUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * The calendar on Android, through the real Calendar Provider: Ruby asks before anything is
 * saved; yes saves, no doesn't; changes and deletions the same. Makes a calendar of its own
 * (removed afterwards), so it runs on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class CalendarDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val zone = ZoneId.systemDefault()
    private var calendarId = -1L

    private fun emulatorOnly() = assumeTrue(
        "Makes its own calendar: emulator only",
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
    )

    private fun asSyncAdapter(uri: Uri) = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "ruby-test")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    @Before
    fun makeCalendar() {
        emulatorOnly()
        for (p in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, p)
        }
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, "ruby-test")
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "Ruby test")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Ruby test")
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, "ruby-test")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFFE0115F.toInt())
        }
        calendarId = ContentUris.parseId(context.contentResolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)!!)
        instrumentation.runOnMainSync { ChatSession.newConversation() }
    }

    @After
    fun removeCalendar() {
        if (calendarId > 0) {
            context.contentResolver.delete(asSyncAdapter(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)), null, null)
        }
        instrumentation.runOnMainSync { ChatSession.newConversation() }
    }

    private val helper by lazy { CalendarHelper(context) }
    private val tomorrow = LocalDate.now().plusDays(1)

    private fun eventsTomorrow() = runBlocking { helper.between(tomorrow, tomorrow).filter { it.calendarId == calendarId } }

    private fun say(text: String): String {
        instrumentation.runOnMainSync { ChatSession.send(context, text) }
        val end = SystemClock.elapsedRealtime() + 10_000
        while (ChatSession.ui.value.sending && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        return ChatSession.ui.value.turns.last().text
    }

    @Test
    fun theCalendarIsFound() = runBlocking {
        val mine = CalendarStore(context).calendars().first { it.id == calendarId }
        assertEquals("Ruby test (on this phone)", mine.label)
        assertEquals(mine, CalendarText.pick("Ruby test", CalendarStore(context).calendars(), null))
    }

    @Test
    fun claudesTool_onlyProposes_nothingIsSaved() = runBlocking {
        val proposed = mutableListOf<CalendarAction>()
        val tools = CalendarTools(context) { action -> proposed += action; "Shall I save it?" }
        val outcome = tools.run(
            CalendarTools.ADD,
            mapOf("title" to "Dentist", "date" to tomorrow.toString(), "time" to "09:30", "calendar" to "Ruby test"),
        )
        assertTrue(outcome.result, outcome.result.startsWith("Not saved yet"))
        assertEquals(1, proposed.size)
        assertEquals(emptyList<CalEvent>(), eventsTomorrow())
        // A calendar that isn't there is an error, not a guess.
        val bad = tools.run(CalendarTools.ADD, mapOf("title" to "X", "date" to tomorrow.toString(), "calendar" to "Narnia"))
        assertTrue(bad.isError)
    }

    @Test
    fun no_savesNothing_yes_saves() = runBlocking {
        val add = helper.add("Dentist", tomorrow.toString(), "09:30", 45, "Ruby test", "Main Street Dental").getOrThrow()
        val question = ChatSession.proposeCalendarForTest(context, add)
        assertTrue(question, question.startsWith("Dentist, ") && question.endsWith("on Ruby test (on this phone). Shall I save it?"))
        assertEquals(question, ChatSession.ui.value.calendarQuestion)

        assertEquals("Okay, I won't change your calendar.", say("No"))
        assertEquals(emptyList<CalEvent>(), eventsTomorrow())

        ChatSession.proposeCalendarForTest(context, add)
        val saved = say("Yes please")
        assertTrue(saved, saved.startsWith("Saved: Dentist, "))
        val events = eventsTomorrow()
        assertEquals(listOf("Dentist"), events.map { it.title })
        assertEquals(45L * 60_000, events.single().endMillis - events.single().startMillis)
        assertEquals("Main Street Dental", events.single().location)
        assertEquals(null, ChatSession.ui.value.calendarQuestion)
    }

    @Test
    fun changeAndDelete_alsoWaitForYes() = runBlocking {
        CalendarStore(context).apply(helper.add("Dentist", tomorrow.toString(), "09:30", null, "Ruby test", null).getOrThrow())
        val event = eventsTomorrow().single()

        // Move it to 11:00: title and length stay.
        val change = helper.change(event.eventId, null, null, "11:00", null, null).getOrThrow()
        ChatSession.proposeCalendarForTest(context, change)
        assertTrue(say("go ahead").startsWith("Changed: Dentist, "))
        val moved = eventsTomorrow().single()
        assertEquals(11, java.time.Instant.ofEpochMilli(moved.startMillis).atZone(zone).hour)
        assertEquals(60L * 60_000, moved.endMillis - moved.startMillis)

        // Delete: no keeps it, yes removes it.
        ChatSession.proposeCalendarForTest(context, helper.delete(moved.eventId).getOrThrow())
        assertEquals("Okay, I won't change your calendar.", say("never mind"))
        assertEquals(1, eventsTomorrow().size)
        ChatSession.proposeCalendarForTest(context, helper.delete(moved.eventId).getOrThrow())
        assertEquals("Deleted: Dentist.", say("Yes"))
        assertEquals(emptyList<CalEvent>(), eventsTomorrow())
    }

    @Test
    fun theYesAndNoButtons() {
        var answered: Boolean? = null
        compose.setContent {
            RubyTheme {
                // In a column, as on the chat and main screens.
                androidx.compose.foundation.layout.Column {
                    ChatInput(
                        ChatUi(hasKey = true, calendarQuestion = "Dentist, tomorrow at 9:30 AM, on Google Calendar. Shall I save it?"),
                        VoiceUi(), hasMicPermission = true,
                        ChatActions(onCalendarAnswer = { answered = it }),
                    )
                }
            }
        }
        compose.onNodeWithText("Dentist, tomorrow at 9:30 AM, on Google Calendar. Shall I save it?").assertIsDisplayed()
        compose.onNodeWithTag("calendar-yes").performClick()
        assertEquals(true, answered)
        compose.onNodeWithTag("calendar-no").performClick()
        assertEquals(false, answered)
    }
}
