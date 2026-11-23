package com.martin.ruby.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MemoryExportTest {

    private val utc = TimeZone.getTimeZone("UTC")

    // 22 Nov 2026 14:32 UTC, and the day after.
    private val nov22 = 1_795_357_920_000L
    private val nov23 = nov22 + 24 * 60 * 60 * 1000L

    private fun msg(time: Long, text: String = "t$time", id: Long = time, role: String = MemoryMessage.ROLE_USER, context: String? = null) =
        MemoryMessage(id = id, timeMillis = time, role = role, text = text, context = context)

    /** Pages of [pageSize] from a sorted list, the way the database gives them. */
    private fun pager(messages: List<MemoryMessage>, pageSize: Int = 2): suspend (MemoryMessage?) -> List<MemoryMessage> = { after ->
        val start = if (after == null) 0 else messages.indexOfFirst { it.id == after.id } + 1
        messages.drop(start).take(pageSize)
    }

    private fun mergeAll(memory: List<MemoryMessage>, bundles: List<MemoryExport.Bundle>): List<MemoryExport.Item> {
        val out = mutableListOf<MemoryExport.Item>()
        runBlocking { MemoryExport.merge(pager(memory), bundles) { out += it } }
        return out
    }

    @Test
    fun merge_putsEverythingInTimeOrder() {
        val memory = listOf(msg(5), msg(9), msg(12), msg(20), msg(21))
        val older = MemoryExport.Bundle(1, MemoryExport.Source.DRIVE_ARCHIVE) { listOf(msg(1, id = 0), msg(3, id = 0)) }
        // A bundle overlapping memory, like messages brought back and then packed again.
        val overlapping = MemoryExport.Bundle(4, MemoryExport.Source.PHONE_ARCHIVE) { listOf(msg(4, id = 0), msg(10, id = 0), msg(25, id = 0)) }

        val items = mergeAll(memory, listOf(overlapping, older))

        assertEquals(listOf(1L, 3, 4, 5, 9, 10, 12, 20, 21, 25), items.map { it.message.timeMillis })
        assertEquals(MemoryExport.Source.DRIVE_ARCHIVE, items[0].source)
        assertEquals(MemoryExport.Source.PHONE_ARCHIVE, items[2].source)
        assertEquals(MemoryExport.Source.MEMORY, items[3].source)
    }

    @Test
    fun merge_opensBundlesOnlyWhenReached() {
        var opened = 0
        val late = MemoryExport.Bundle(100, MemoryExport.Source.PHONE_ARCHIVE) { opened++; listOf(msg(100, id = 0)) }
        val seenBeforeOpening = mutableListOf<Long>()
        runBlocking {
            MemoryExport.merge(pager(listOf(msg(1), msg(2), msg(3))), listOf(late)) {
                if (opened == 0) seenBeforeOpening += it.message.timeMillis
            }
        }
        assertEquals(listOf(1L, 2, 3), seenBeforeOpening)
        assertEquals(1, opened)
    }

    @Test
    fun merge_skipsBundlesThatCantOpen() {
        val broken = MemoryExport.Bundle(1, MemoryExport.Source.DRIVE_ARCHIVE) { null }
        val items = mergeAll(listOf(msg(2)), listOf(broken))
        assertEquals(listOf(2L), items.map { it.message.timeMillis })
    }

    @Test
    fun merge_emptyMemory() {
        assertTrue(mergeAll(emptyList(), emptyList()).isEmpty())
        val onlyBundle = MemoryExport.Bundle(1, MemoryExport.Source.PHONE_ARCHIVE) { listOf(msg(1, id = 0)) }
        assertEquals(1, mergeAll(emptyList(), listOf(onlyBundle)).size)
    }

    @Test
    fun quote_escapesEverythingJsonNeeds() {
        assertEquals("\"a \\\"b\\\" \\\\ c\\nd\\te\\r\"", MemoryExport.quote("a \"b\" \\ c\nd\te\r"))
        assertEquals("\"\\u0001\"", MemoryExport.quote("\u0001"))
        // Emoji and accents stay as they are (the file is UTF-8).
        assertEquals("\"café 🚗\"", MemoryExport.quote("café 🚗"))
    }

    private fun write(format: MemoryExport.Format, items: List<MemoryExport.Item>, summary: MemoryExport.Summary, car: MemoryExport.Car? = null, places: List<MemoryExport.Place> = emptyList()): String {
        val writer = StringWriter()
        val output = MemoryExport.output(format, writer, Locale.US, utc)
        output.begin(nov23 + 1000, car, places)
        items.forEach(output::message)
        output.end(summary)
        // Newer Java puts a narrow no-break space before "PM".
        return writer.toString().replace(' ', ' ')
    }

    @Test
    fun text_groupsByDay_andIndentsLaterLines() {
        val items = listOf(
            MemoryExport.Item(msg(nov22, "Parked on level 3.", context = "Driving"), MemoryExport.Source.MEMORY),
            MemoryExport.Item(msg(nov22 + 60_000, "Noted.", role = MemoryMessage.ROLE_RUBY), MemoryExport.Source.MEMORY),
            MemoryExport.Item(msg(nov23, "Line one\nline two"), MemoryExport.Source.PHONE_ARCHIVE),
        )
        val text = write(MemoryExport.Format.TEXT, items, MemoryExport.Summary(2, 1, 0, emptyList()))

        assertTrue(text, text.contains("== Sunday, November 22, 2026 ==\n2:32 PM · You · Driving: Parked on level 3.\n2:33 PM · Ruby: Noted.\n"))
        assertTrue(text, text.contains("== Monday, November 23, 2026 ==\n2:32 PM · You: Line one\n    line two\n"))
        assertTrue(text, text.contains("This file isn't encrypted."))
        assertTrue(text, text.trimEnd().endsWith("3 messages."))
    }

    @Test
    fun text_listsWhatWasLeftOut_andHasNoPlaces() {
        val text = write(
            MemoryExport.Format.TEXT, emptyList(), MemoryExport.Summary(0, 0, 0, listOf("64 messages from X, in Google Drive: offline")),
            car = MemoryExport.Car("Corolla", "AA:BB"), places = listOf(MemoryExport.Place("Home", 1.0, 2.0)),
        )
        assertTrue(text, text.contains("Memory is empty."))
        assertTrue(text, text.contains("Not included:\n- 64 messages from X, in Google Drive: offline\n"))
        assertFalse(text.contains("Corolla"))
        assertFalse(text.contains("Home"))
    }

    @Test
    fun json_hasEveryField_placesAndCar() {
        val items = listOf(
            MemoryExport.Item(msg(nov22, "Say \"hi\"\n", context = "At Home"), MemoryExport.Source.MEMORY),
            MemoryExport.Item(msg(nov23, "x", role = MemoryMessage.ROLE_RUBY), MemoryExport.Source.DRIVE_ARCHIVE),
        )
        val json = write(
            MemoryExport.Format.JSON, items, MemoryExport.Summary(1, 0, 1, listOf("one \"part\"")),
            car = MemoryExport.Car("Corolla", "AA:BB"), places = listOf(MemoryExport.Place("Home", 51.5, -0.12)),
        )
        assertTrue(json, json.contains("\"format\": \"ruby-memory-export\""))
        assertTrue(json, json.contains("\"car\": {\"name\": \"Corolla\", \"address\": \"AA:BB\"}"))
        assertTrue(json, json.contains("{\"name\": \"Home\", \"latitude\": 51.5, \"longitude\": -0.12}"))
        assertTrue(json, json.contains(
            "{\"time\": \"2026-11-22T14:32:00.000Z\", \"timeMillis\": $nov22, \"role\": \"user\", " +
                "\"context\": \"At Home\", \"source\": \"memory\", \"text\": \"Say \\\"hi\\\"\\n\"}"
        ))
        assertTrue(json, json.contains("\"role\": \"ruby\", \"context\": null, \"source\": \"drive-archive\""))
        assertTrue(json, json.contains("\"summary\": {\"messages\": 2, \"fromMemory\": 1, \"fromPhoneArchive\": 0, \"fromDrive\": 1, \"notIncluded\": [\"one \\\"part\\\"\"]}"))
        assertBalanced(json)
    }

    @Test
    fun json_emptyMemory_isStillValid() {
        val json = write(MemoryExport.Format.JSON, emptyList(), MemoryExport.Summary(0, 0, 0, emptyList()))
        assertTrue(json, json.contains("\"car\": null"))
        assertTrue(json, json.contains("\"places\": []"))
        assertTrue(json, json.contains("\"messages\": []"))
        assertFalse(json, json.contains(",\n}"))
        assertBalanced(json)
    }

    /** Brackets and braces match outside strings, and there's no trailing comma. */
    private fun assertBalanced(json: String) {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var lastSignificant = ' '
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> {
                    assertFalse("Trailing comma before $c", lastSignificant == ',')
                    val open = stack.removeLast()
                    assertEquals(if (c == '}') '{' else '[', open)
                }
            }
            if (!c.isWhitespace()) lastSignificant = c
        }
        assertTrue(stack.isEmpty())
        assertFalse(inString)
    }

    @Test
    fun describeRange_andFileName() {
        assertEquals("64 messages from Nov 22, 2026 to Nov 23, 2026", MemoryExport.describeRange(64, nov22, nov23, Locale.US, utc))
        assertEquals("1 message from Nov 22, 2026", MemoryExport.describeRange(1, nov22, nov22, Locale.US, utc))
        assertTrue(MemoryExport.fileName(MemoryExport.Format.JSON, Date(nov22)).matches(Regex("ruby-memory-2026-11-2[123]\\.json")))
    }
}
