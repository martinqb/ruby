package com.martin.ruby.memory

import java.io.Writer
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import java.util.TimeZone

/**
 * The parts of export that don't need Android: putting messages from memory and from the
 * archived bundles into one time order, and writing them as text or JSON. Unit tested.
 */
object MemoryExport {

    enum class Format(val extension: String, val mimeType: String) {
        TEXT("txt", "text/plain"),
        JSON("json", "application/json"),
    }

    /** Where an exported message is kept now. */
    enum class Source(val json: String) {
        MEMORY("memory"),
        PHONE_ARCHIVE("phone-archive"),
        DRIVE_ARCHIVE("drive-archive"),
    }

    class Item(val message: MemoryMessage, val source: Source)

    /** A packed-away bundle; [load] opens it, or returns null if it can't (and says why elsewhere). */
    class Bundle(val firstMillis: Long, val source: Source, val load: suspend () -> List<MemoryMessage>?)

    /** "ruby-memory-2026-11-22.txt" */
    fun fileName(format: Format, now: Date = Date()): String =
        "ruby-memory-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + "." + format.extension

    /**
     * Calls [emit] for every message, oldest first. Memory is read a page at a time with
     * [nextPage] (given the last message of the previous page, or null for the first), and
     * a bundle is only opened once the export reaches its time, so only bundles whose
     * times overlap are held in RAM together.
     */
    suspend fun merge(
        nextPage: suspend (after: MemoryMessage?) -> List<MemoryMessage>,
        bundles: List<Bundle>,
        emit: suspend (Item) -> Unit,
    ) {
        val sorted = bundles.sortedBy { it.firstMillis }
        var order = 0L
        class Pending(val item: Item, val order: Long)
        val opened = PriorityQueue<Pending>(compareBy<Pending>({ it.item.message.timeMillis }, { it.order }))

        var page = nextPage(null)
        var index = 0
        var memoryDone = page.isEmpty()
        var nextBundle = 0
        while (true) {
            if (!memoryDone && index == page.size) {
                page = nextPage(page.last())
                index = 0
                memoryDone = page.isEmpty()
            }
            val fromMemory = page.getOrNull(index)
            val fromBundle = opened.peek()
            val nextTime = listOfNotNull(fromMemory?.timeMillis, fromBundle?.item?.message?.timeMillis).minOrNull()

            // Open the next bundle once nothing earlier is left to write.
            if (nextBundle < sorted.size && (nextTime == null || sorted[nextBundle].firstMillis <= nextTime)) {
                val bundle = sorted[nextBundle++]
                bundle.load()?.sortedBy { it.timeMillis }?.forEach { opened.add(Pending(Item(it, bundle.source), order++)) }
                continue
            }
            if (nextTime == null) break
            if (fromBundle != null && (fromMemory == null || fromBundle.item.message.timeMillis <= fromMemory.timeMillis)) {
                emit(opened.poll()!!.item)
            } else {
                emit(Item(fromMemory!!, Source.MEMORY))
                index++
            }
        }
    }

    /** What the export held, written at the end of the file and shown on screen. */
    class Summary(
        val fromMemory: Int,
        val fromPhoneArchive: Int,
        val fromDrive: Int,
        /** Bundles that couldn't be included, and why. */
        val skipped: List<String>,
    ) {
        val total get() = fromMemory + fromPhoneArchive + fromDrive
    }

    class Place(val name: String, val latitude: Double, val longitude: Double)
    class Car(val name: String, val address: String)

    /** Writes an export a message at a time. */
    interface Output {
        fun begin(exportedMillis: Long, car: Car?, places: List<Place>)
        fun message(item: Item)
        fun end(summary: Summary)
    }

    fun output(format: Format, writer: Writer, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): Output =
        when (format) {
            Format.TEXT -> TextOutput(writer, locale, zone)
            Format.JSON -> JsonOutput(writer)
        }

    /** "64 messages from 3 Jan 2026 to 5 Feb 2026" */
    fun describeRange(count: Int, firstMillis: Long, lastMillis: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val format = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).apply { timeZone = zone }
        val first = format.format(Date(firstMillis))
        val last = format.format(Date(lastMillis))
        val messages = if (count == 1) "1 message" else "$count messages"
        return if (first == last) "$messages from $first" else "$messages from $first to $last"
    }

    /**
     * Readable text, grouped by day:
     *
     *     == Sunday, November 22, 2026 ==
     *     2:32 PM · You · Driving: Parked on level 3.
     *
     * Places and the car are only in the JSON export.
     */
    private class TextOutput(private val out: Writer, private val locale: Locale, private val zone: TimeZone) : Output {
        private val day = SimpleDateFormat("EEEE, ", locale).apply { timeZone = zone }
        private val date = DateFormat.getDateInstance(DateFormat.LONG, locale).apply { timeZone = zone }
        private val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).apply { timeZone = zone }
        private val dateTime = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply { timeZone = zone }
        private var lastDay: String? = null

        override fun begin(exportedMillis: Long, car: Car?, places: List<Place>) {
            out.write("Ruby's memory\n")
            out.write("Exported ${dateTime.format(Date(exportedMillis))}. This file isn't encrypted.\n")
        }

        override fun message(item: Item) {
            val message = item.message
            val whenDate = Date(message.timeMillis)
            val thisDay = day.format(whenDate) + date.format(whenDate)
            if (thisDay != lastDay) {
                out.write("\n== $thisDay ==\n")
                lastDay = thisDay
            }
            val who = if (message.role == MemoryMessage.ROLE_RUBY) "Ruby" else "You"
            val heading = listOfNotNull(time.format(whenDate), who, message.context).joinToString(" · ")
            // Later lines of a message are indented, so each message's start is easy to see.
            val text = message.text.replace("\r\n", "\n").replace("\n", "\n    ")
            out.write("$heading: $text\n")
        }

        override fun end(summary: Summary) {
            out.write("\n")
            if (summary.total == 0) out.write("Memory is empty.\n")
            else out.write(if (summary.total == 1) "1 message.\n" else "${summary.total} messages.\n")
            if (summary.skipped.isNotEmpty()) {
                out.write("Not included:\n")
                summary.skipped.forEach { out.write("- $it\n") }
            }
        }
    }

    /**
     * Every detail, for other apps:
     * `{"format": "ruby-memory-export", "version": 1, "exported": ..., "car": ..., "places": [...],
     * "messages": [...], "summary": {...}}`. Times are UTC (ISO 8601) and milliseconds.
     */
    private class JsonOutput(private val out: Writer) : Output {
        private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        private var first = true

        override fun begin(exportedMillis: Long, car: Car?, places: List<Place>) {
            out.write("{\n")
            out.write("  \"format\": \"ruby-memory-export\",\n")
            out.write("  \"version\": 1,\n")
            out.write("  \"exported\": ${quote(iso.format(Date(exportedMillis)))},\n")
            out.write("  \"car\": ")
            out.write(car?.let { "{\"name\": ${quote(it.name)}, \"address\": ${quote(it.address)}}" } ?: "null")
            out.write(",\n  \"places\": [")
            places.forEachIndexed { i, place ->
                out.write(if (i == 0) "\n" else ",\n")
                out.write(
                    "    {\"name\": ${quote(place.name)}, \"latitude\": ${number(place.latitude)}, " +
                        "\"longitude\": ${number(place.longitude)}}"
                )
            }
            out.write(if (places.isEmpty()) "],\n" else "\n  ],\n")
            out.write("  \"messages\": [")
        }

        override fun message(item: Item) {
            val m = item.message
            out.write(if (first) "\n" else ",\n")
            first = false
            out.write(
                "    {\"time\": ${quote(iso.format(Date(m.timeMillis)))}, \"timeMillis\": ${m.timeMillis}, " +
                    "\"role\": ${quote(m.role)}, \"context\": ${m.context?.let(::quote) ?: "null"}, " +
                    "\"source\": ${quote(item.source.json)}, \"text\": ${quote(m.text)}}"
            )
        }

        override fun end(summary: Summary) {
            out.write(if (first) "],\n" else "\n  ],\n")
            out.write("  \"summary\": {")
            out.write("\"messages\": ${summary.total}, \"fromMemory\": ${summary.fromMemory}, ")
            out.write("\"fromPhoneArchive\": ${summary.fromPhoneArchive}, \"fromDrive\": ${summary.fromDrive}, ")
            out.write("\"notIncluded\": [${summary.skipped.joinToString(", ") { quote(it) }}]}\n")
            out.write("}\n")
        }

        private fun number(value: Double): String =
            if (value.isNaN() || value.isInfinite()) "null" else value.toString()
    }

    /** A JSON string, with quotes, backslashes and control characters escaped. */
    fun quote(text: String): String = buildString(text.length + 2) {
        append('"')
        for (c in text) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
