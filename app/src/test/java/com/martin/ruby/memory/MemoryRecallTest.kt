package com.martin.ruby.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MemoryRecallTest {

    @Test
    fun meaningfulWords_matchAnyOfThem() {
        assertEquals(
            "\"sister\"* OR \"birthday\"*",
            MemoryRecall.ftsQuery("What did I tell you about my sister's birthday?"),
        )
    }

    @Test
    fun numbersAreKept_shortWordsAndRepeatsDropped() {
        assertEquals("\"tyre\"* OR \"36\"* OR \"psi\"*", MemoryRecall.ftsQuery("Tyre at 36 psi, is tyre ok?"))
    }

    @Test
    fun ftsSyntax_isTreatedAsText() {
        assertEquals("\"near\"* OR \"park\"*", MemoryRecall.ftsQuery("\" NEAR park* -"))
    }

    @Test
    fun onlyCommonWords_isNull() {
        assertNull(MemoryRecall.ftsQuery("What do you think about that?"))
        assertNull(MemoryRecall.ftsQuery(""))
    }

    @Test
    fun format_listsOldestFirstWithTimeContextAndWho() {
        val zone = ZoneId.of("UTC")
        val earlier = millis(2026, 11, 3, 8, 14)
        val later = millis(2026, 11, 4, 18, 30)
        val text = MemoryRecall.format(
            listOf(
                MemoryMessage(timeMillis = later, role = MemoryMessage.ROLE_RUBY, text = "Happy birthday to her!"),
                MemoryMessage(timeMillis = earlier, role = MemoryMessage.ROLE_USER, text = "My sister's\nbirthday is Friday", context = "Driving"),
            ),
            zone,
        )!!
        val lines = text.lines()
        assertTrue(lines[0].startsWith("From Ruby's memory"))
        assertEquals("- Tue, Nov 3, 2026, 8:14 AM, while Driving, the user said: My sister's birthday is Friday", lines[1])
        assertEquals("- Wed, Nov 4, 2026, 6:30 PM, you (Ruby) said: Happy birthday to her!", lines[2])
    }

    @Test
    fun format_cutsLongMemories() {
        val long = "word ".repeat(300)
        val text = MemoryRecall.format(listOf(MemoryMessage(timeMillis = 0, role = "user", text = long)), ZoneId.of("UTC"))!!
        assertTrue(text.lines()[1].endsWith("…"))
        assertTrue(text.lines()[1].length < MemoryRecall.MAX_CHARS + 80)
    }

    @Test
    fun format_nothing_isNull() {
        assertNull(MemoryRecall.format(emptyList()))
    }

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.UTC).toEpochMilli()
}
