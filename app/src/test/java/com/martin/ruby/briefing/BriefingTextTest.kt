package com.martin.ruby.briefing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Steps 1 and 2: what's sent to Claude, reading the answer back, length, and daily variety. */
class BriefingTextTest {

    private val zone = ZoneId.of("America/Chicago")
    private val now = LocalDateTime.of(2026, 11, 24, 7, 42)
    private fun millis(h: Int, m: Int) = now.withHour(h).withMinute(m).atZone(zone).toInstant().toEpochMilli()

    private val everything = BriefingProfile(BriefingModule.entries.toSet(), Interest.SKY_TONIGHT)

    @Test
    fun spokenOncePerMorning_onlyBetween4AndNoon() {
        assertTrue(BriefingText.shouldSpeak(true, now, "2026-11-23"))
        assertFalse("already today", BriefingText.shouldSpeak(true, now, "2026-11-24"))
        assertFalse("turned off", BriefingText.shouldSpeak(false, now, null))
        assertFalse("3 AM", BriefingText.shouldSpeak(true, now.withHour(3), null))
        assertTrue("4 AM", BriefingText.shouldSpeak(true, now.withHour(4).withMinute(0), null))
        assertFalse("noon", BriefingText.shouldSpeak(true, now.withHour(12).withMinute(0), null))
    }

    @Test
    fun thePrompt_hasOnlyTheChosenSegments_inOrder_withTheirLengths() {
        val facts = BriefingFacts(
            now = now,
            profile = BriefingProfile(), // new profile: no interest
            events = listOf(CalendarEvent("Dentist", millis(9, 30), millis(10, 0), false, "Main St Dental")),
            headsUp = listOf("Rain likely from about 3:00 PM (70% chance): an umbrella would help."),
            music = listOf(MusicMoment(1971, "Led Zeppelin released their fourth album.", "event")),
        )
        val prompt = BriefingText.prompt(facts, zone)
        val tags = Regex("^\\[([a-z]+)]", RegexOption.MULTILINE).findAll(prompt).map { it.groupValues[1] }.toList()
        assertEquals(listOf("greeting", "glance", "music", "headsup", "signoff"), tags)
        assertTrue(prompt.contains("[glance] About 70 words."))
        assertTrue(prompt.contains("Tuesday, November 24, 2026"))
        assertTrue(prompt.contains("- 9:30 AM to 10:00 AM: Dentist (at Main St Dental)"))
        assertTrue(prompt.contains("- 1971: Led Zeppelin released their fourth album."))
        assertTrue(prompt.contains("Rain likely from about 3:00 PM"))
    }

    @Test
    fun segmentsWithNothingToSay_areLeftOut() {
        val facts = BriefingFacts(now = now, profile = everything) // no heads-up notes, no sky, no music
        assertEquals(listOf(BriefingModule.GREETING, BriefingModule.GLANCE, BriefingModule.SIGN_OFF), facts.segments)
    }

    @Test
    fun claudesAnswer_isReadBackBySegment() {
        val answer = """
            [greeting] Good morning, and happy Tuesday!
            [glance] It's 31 degrees and overcast.
            You have a dentist appointment at nine thirty.
            [headsup] Rain arrives around three, so grab an umbrella.
            [weather] (an unknown tag is ignored)
            [signoff] Have a great day!
        """.trimIndent()
        val expected = listOf(BriefingModule.GREETING, BriefingModule.GLANCE, BriefingModule.HEADS_UP, BriefingModule.SIGN_OFF)
        val b = BriefingText.parse(answer, expected)
        assertEquals(expected, b.parts.map { it.first })
        assertEquals("It's 31 degrees and overcast. You have a dentist appointment at nine thirty.", b.part(BriefingModule.GLANCE))
        assertEquals("Good morning, and happy Tuesday! It's 31 degrees and overcast. You have a dentist appointment at nine thirty. " +
            "Rain arrives around three, so grab an umbrella. Have a great day!", b.text)
        // Without tags, the whole answer is kept.
        assertEquals("Good morning. All clear.", BriefingText.parse("Good morning.\nAll clear.", expected).text)
    }

    @Test
    fun tooLong_isTrimmedUnderTwoMinutes_keepingTheGreetingAndSignOff() {
        val long = (1..40).joinToString(" ") { "This is sentence number $it of the glance." }
        val b = BriefingText.fit(
            Briefing(listOf(BriefingModule.GREETING to "Good morning!", BriefingModule.GLANCE to long, BriefingModule.SIGN_OFF to "Have a great day!"))
        )
        assertTrue("${b.words} words", b.words <= BriefingText.MAX_WORDS)
        assertEquals("Good morning!", b.part(BriefingModule.GREETING))
        assertEquals("Have a great day!", b.part(BriefingModule.SIGN_OFF))
        assertTrue(b.part(BriefingModule.GLANCE)!!.startsWith("This is sentence number 1 of the glance."))
        assertTrue(b.part(BriefingModule.GLANCE)!!.endsWith("."))
    }

    @Test
    fun greetingsAndSignOffs_changeEveryDay_forTwoWeeks() {
        var recentGreetings = emptyList<String>()
        var recentSignOffs = emptyList<String>()
        val greetings = mutableListOf<String>()
        val signOffs = mutableListOf<String>()
        for (d in 0 until 14) {
            val date = LocalDate.of(2026, 11, 24).plusDays(d.toLong())
            val g = BriefingText.greetingFor(date, recentGreetings)
            val s = BriefingText.signOffFor(date, recentSignOffs)
            greetings += g
            signOffs += s
            recentGreetings = BriefingText.remember(recentGreetings, g)
            recentSignOffs = BriefingText.remember(recentSignOffs, s)
        }
        assertEquals("14 different greetings: $greetings", 14, greetings.map(BriefingText::normalize).toSet().size)
        assertEquals("14 different sign-offs: $signOffs", 14, signOffs.map(BriefingText::normalize).toSet().size)
        assertTrue(greetings.none { "%s" in it })
        // The weekday fills in where a line has room for it.
        assertTrue(greetings.any { "Tuesday" in it || "Wednesday" in it || "Friday" in it })
    }

    @Test
    fun recentLines_areKeptForTwoWeeks_andSentToClaudeToAvoid() {
        var recent = emptyList<String>()
        for (i in 1..20) recent = BriefingText.remember(recent, "Line $i")
        assertEquals(BriefingText.RECENT_KEPT, recent.size)
        assertEquals("Line 20", recent.first())
        // Saying one again moves it to the front instead of repeating it.
        recent = BriefingText.remember(recent, "line 15!")
        assertEquals(1, recent.count { BriefingText.normalize(it) == "line15" })

        val prompt = BriefingText.prompt(BriefingFacts(now = now, recentGreetings = listOf("Rise and shine, it's Monday!")), zone)
        assertTrue(prompt.contains("- \"Rise and shine, it's Monday!\""))
        assertNotEquals(
            BriefingText.normalize("Rise and shine, it's Monday!"),
            BriefingText.normalize(BriefingText.greetingFor(LocalDate.of(2026, 11, 23), listOf("Rise and shine, it's Monday!"))),
        )
    }

    @Test
    fun thePlainBriefing_coversEachSegment_andFits() {
        val facts = BriefingFacts(
            now = now,
            profile = everything,
            forecast = ForecastTest.forecast(),
            events = listOf(
                CalendarEvent("Dentist", millis(9, 30), millis(10, 0), false),
                CalendarEvent("Mom's birthday", millis(0, 0), millis(23, 59), true),
            ),
            reminders = listOf(BriefingReminder("call Mom", dueMillis = millis(17, 30)), BriefingReminder("buy milk", place = "Grocery")),
            headsUp = listOf("Rain likely from about 3:00 PM (70% chance): an umbrella would help."),
            sky = SkyTonightTest.sampleSky(),
            music = listOf(MusicMoment(1971, "Led Zeppelin released their fourth album", "event")),
        )
        val b = BriefingText.plain(facts, zone)
        assertEquals(BriefingModule.entries.toList(), b.parts.map { it.first })
        assertTrue(b.text, b.text.contains("You have 2 events today: Mom's birthday, all day, and Dentist at 9:30 AM."))
        assertTrue(b.text, b.text.contains("Reminder: call Mom, at 5:30 PM."))
        assertTrue(b.text, b.text.contains("On this day in 1971: Led Zeppelin released their fourth album."))
        assertTrue(b.text, b.text.contains("Tonight's chance of clear skies is good."))
        assertTrue(b.text, b.text.contains("Tonight there's a waxing gibbous moon, 78 percent lit."))
        assertTrue(b.words <= BriefingText.MAX_WORDS)
    }
}
