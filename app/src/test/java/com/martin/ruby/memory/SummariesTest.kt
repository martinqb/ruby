package com.martin.ruby.memory

import com.martin.ruby.memory.MemoryRecall.Recalled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SummariesTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun msg(id: Long, day: Int, hour: Int, text: String, role: String = MemoryMessage.ROLE_USER, context: String? = null) =
        MemoryMessage(id = id, timeMillis = at(day, hour), role = role, text = text, context = context)

    private fun summary(day: String, text: String = "Summary of $day") =
        DaySummary(day = day, text = text, messageCount = 3, createdMillis = 0)

    // Days and the cutoff

    @Test
    fun dayKeys_useThePhonesTimeZone() {
        // 11:30 PM in New York is already the next day in UTC.
        assertEquals("2026-11-03", SummaryText.dayOf(at(3, 23, 30), zone))
        assertEquals("2026-11-04", SummaryText.dayOf(at(3, 23, 30), ZoneId.of("UTC")))
        assertEquals("Tue, Nov 3, 2026", SummaryText.label("2026-11-03"))
    }

    @Test
    fun onlyDaysMoreThanAWeekOld_areDue() {
        val now = ZonedDateTime.of(2026, 11, 23, 15, 0, 0, 0, zone)
        // Days before Nov 16 are summarized; Nov 16 onward still count as recent.
        assertEquals(at(16, 0), Summaries.cutoffMillis(now))
    }

    @Test
    fun theDebugRun_includesToday() {
        val now = ZonedDateTime.of(2026, 11, 23, 15, 0, 0, 0, zone)
        assertEquals(at(24, 0), Summaries.cutoffMillis(now, Summaries.INCLUDE_TODAY))
    }

    // What's sent to be summarized

    @Test
    fun lines_showTimeContextAndWho() {
        val lines = SummaryText.lines(
            listOf(
                msg(1, 3, 8, "Tyre pressure is 36 psi", context = "Driving"),
                msg(2, 3, 9, "  Noted.  ", role = MemoryMessage.ROLE_RUBY),
            ),
            zone,
        )
        assertEquals(listOf("[8:00 AM, Driving] User: Tyre pressure is 36 psi", "[9:00 AM] Ruby: Noted."), lines)
    }

    @Test
    fun aLongDay_isSplitIntoPartsWithoutLosingOrSplittingAMessage() {
        val lines = (1..10).map { "line $it " + "x".repeat(30) } // about 38 characters each
        val parts = SummaryText.parts(lines, maxChars = 100)
        assertEquals(lines, parts.flatMap { it.split("\n") })
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(listOf("only"), SummaryText.parts(listOf("only"), maxChars = 100))
        // A single message longer than a part still goes, in a part of its own.
        val huge = "y".repeat(250)
        assertEquals(listOf("a", huge, "b"), SummaryText.parts(listOf("a", huge, "b"), maxChars = 100))
    }

    // Recall: messages and summaries

    @Test
    fun withoutSummaries_recallIsTheMessagesAsBefore() {
        val ranked = (1L..7L).map { msg(it, 3, 8, "m$it") }
        val chosen = MemoryRecall.choose(ranked, { null }, emptyList())
        assertEquals(ranked.take(MemoryRecall.MAX_MEMORIES).map { Recalled.Message(it) }, chosen)
    }

    @Test
    fun theBestMatchesStayWordForWord_weakerOnesBecomeTheirDaysSummary() {
        val nov3 = summary("2026-11-03")
        val ranked = listOf(msg(1, 3, 8, "a"), msg(2, 3, 9, "b"), msg(3, 3, 10, "c"), msg(4, 3, 11, "d"))
        val chosen = MemoryRecall.choose(ranked, { nov3 }, emptyList())
        // Two kept as they are, then one summary standing in for the rest of the day.
        assertEquals(listOf(Recalled.Message(ranked[0]), Recalled.Message(ranked[1]), Recalled.Summary(nov3)), chosen)
    }

    @Test
    fun matchingSummaries_getPlacesEvenWhenManyMessagesMatch() {
        val ranked = (1L..9L).map { msg(it, 20, 8, "m$it") } // a recent day: no summaries
        val oct = listOf(summary("2026-10-02"), summary("2026-10-05"), summary("2026-10-09"))
        val chosen = MemoryRecall.choose(ranked, { null }, oct)
        assertEquals(MemoryRecall.MAX_MEMORIES, chosen.size)
        assertEquals(MemoryRecall.SUMMARY_SLOTS, chosen.count { it is Recalled.Summary })
        assertEquals(listOf(Recalled.Summary(oct[0]), Recalled.Summary(oct[1])), chosen.filterIsInstance<Recalled.Summary>())
    }

    @Test
    fun aDaysSummary_isSentOnlyOnce() {
        val nov3 = summary("2026-11-03")
        val ranked = (1L..6L).map { msg(it, 3, 8, "m$it") }
        val chosen = MemoryRecall.choose(ranked, { nov3 }, listOf(nov3))
        assertEquals(1, chosen.count { it is Recalled.Summary })
    }

    @Test
    fun theNote_listsMessagesAndSummariesOldestFirst() {
        val note = MemoryRecall.formatRecalled(
            listOf(Recalled.Message(msg(1, 20, 8, "Parked on level 3")), Recalled.Summary(summary("2026-11-03", "The user booked a tyre check."))),
            zone,
        )!!
        val lines = note.lines()
        assertTrue(lines[0].startsWith("From Ruby's memory"))
        assertEquals("- Summary of Tue, Nov 3, 2026: The user booked a tyre check.", lines[1])
        assertEquals("- Fri, Nov 20, 2026, 8:00 AM, the user said: Parked on level 3", lines[2])
    }

    @Test
    fun runResult_describesWhatHappened() {
        assertEquals("Summarized 1 day.", Summaries.RunResult(1, 0, false, null).describe())
        assertEquals(
            "Summarized 14 days. 1 couldn't be summarized. More to do on the next run.",
            Summaries.RunResult(14, 1, true, null).describe(),
        )
        assertEquals(
            "Summarized 2 days. Stopped: Couldn't reach Anthropic.",
            Summaries.RunResult(2, 0, true, "Couldn't reach Anthropic.").describe(),
        )
    }
}
