package com.martin.ruby.briefing

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.voice.KokoroModel
import com.martin.ruby.voice.KokoroSpeaker
import com.martin.ruby.voice.KokoroVoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The briefing on Android itself: the calendar, the sky library, the saved profile, and a
 * whole briefing spoken by Kokoro and timed. Changes settings and plays sound, so it runs
 * on the emulator only.
 */
@RunWith(AndroidJUnit4::class)
class BriefingDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun emulatorOnly() = assumeTrue(
        "Changes settings and plays sound: emulator only",
        Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
    )

    private fun log(message: String) = Log.i("BriefingTest", message)

    @Test
    fun step1_theProfileIsSavedOnThePhone() = runBlocking {
        emulatorOnly()
        val before = MorningBriefing.state(context).first().profile
        try {
            val mine = BriefingProfile(BriefingModule.entries.toSet(), Interest.SKY_TONIGHT)
            MorningBriefing.saveProfile(context, mine)
            val saved = waitFor { MorningBriefing.state(context).first().profile.takeIf { it == mine } }
            assertEquals(mine, saved)
        } finally {
            MorningBriefing.saveProfile(context, before)
        }
    }

    @Test
    fun step3_todaysCalendar_isRead() {
        emulatorOnly()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CALENDAR)
        val events = CalendarReader.eventsOn(context, LocalDate.now())
        assertNotNull("With permission, a list (maybe empty) comes back", events)
        log("Calendar today: ${events!!.size} events")
    }

    @Test
    fun step5_tonightsSky_isWorkedOutOnAndroid() {
        val started = SystemClock.elapsedRealtime()
        val sky = SkyTonight.compute(39.10, -94.58, LocalDate.of(2025, 1, 21), ZoneId.of("America/Chicago"), null)
        val took = SystemClock.elapsedRealtime() - started
        log("Sky in $took ms: ${sky.moonPhase}, ${sky.moonLit}% lit; planets ${sky.planets.map { it.name }}")
        assertTrue(sky.planets.map { it.name }.containsAll(listOf("Venus", "Jupiter", "Mars", "Saturn")))
        assertTrue("Quick enough: $took ms", took < 5_000)
    }

    @Test
    fun step2to6_aWholeBriefing_isSpokenInUnderTwoMinutes() {
        emulatorOnly()
        assumeTrue("Needs the Kokoro voice installed", KokoroModel.isInstalled(context))
        // Everything on, a full day: as long as a briefing gets.
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone).withHour(7).withMinute(42)
        fun at(h: Int, m: Int) = now.withHour(h).withMinute(m).atZone(zone).toInstant().toEpochMilli()
        val facts = BriefingFacts(
            now = now,
            profile = BriefingProfile(BriefingModule.entries.toSet(), Interest.SKY_TONIGHT),
            forecast = null,
            events = listOf(
                CalendarEvent("Team stand-up", at(9, 0), at(9, 15), false),
                CalendarEvent("Dentist", at(13, 30), at(14, 0), false, "Main Street Dental"),
                CalendarEvent("Mom's birthday", at(0, 0), at(23, 59), true),
            ),
            reminders = listOf(BriefingReminder("call Mom", dueMillis = at(17, 30)), BriefingReminder("buy milk", place = "Grocery")),
            headsUp = listOf("Rain likely from about 3:00 PM (70% chance): an umbrella would help.", "Strong gusts up to 42 mph today."),
            sky = SkyTonight.compute(39.10, -94.58, now.toLocalDate(), zone, null),
            music = listOf(MusicMoment(1966, "The Beatles began recording Strawberry Fields Forever.", "event")),
        )
        val briefing = BriefingText.plain(facts, zone)
        log("Briefing (${briefing.words} words): ${briefing.text}")
        assertTrue(briefing.words <= BriefingText.MAX_WORDS)

        val voice = KokoroVoice(context, KokoroSpeaker.HEART)
        try {
            val started = SystemClock.elapsedRealtime()
            voice.say(briefing.text)
            assertTrue("She should start", waitUntil(20_000) { voice.playedFrames() > 0 })
            assertTrue("She should finish within 2 minutes", waitUntil(150_000) { !voice.speaking.value })
            val seconds = voice.playedFrames() / 24_000f
            log("Spoken in %.1f s of audio (%d s from start)".format(seconds, (SystemClock.elapsedRealtime() - started) / 1000))
            assertTrue("Under two minutes: $seconds s", seconds < 120f)
            assertTrue("The whole briefing was heard: $seconds s", seconds > briefing.words / 4f)
        } finally {
            voice.close()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private suspend fun <T> waitFor(read: suspend () -> T?): T? {
        repeat(50) {
            read()?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        return read()
    }
}
