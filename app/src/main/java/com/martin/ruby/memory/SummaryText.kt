package com.martin.ruby.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Turning a day of messages into what's sent to be summarized. Kept free of Android for tests. */
object SummaryText {

    /** A day's transcript longer than this is summarized in parts, then the parts combined. */
    const val PART_CHARS = 100_000

    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY_LABEL = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    /** The day key used in the summaries table, e.g. "2026-11-23", for a time in [zone]. */
    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /** "Mon, Nov 23, 2026" for a day key. */
    fun label(day: String): String = DAY_LABEL.format(LocalDate.parse(day))

    /** One line per message: "[8:14 AM, Driving] User: ...", "[8:15 AM] Ruby: ...". */
    fun lines(messages: List<MemoryMessage>, zone: ZoneId = ZoneId.systemDefault()): List<String> = messages.map { m ->
        val time = TIME.format(Instant.ofEpochMilli(m.timeMillis).atZone(zone))
        val where = m.context?.let { ", $it" } ?: ""
        val who = if (m.role == MemoryMessage.ROLE_RUBY) "Ruby" else "User"
        "[$time$where] $who: ${m.text.trim()}"
    }

    /**
     * Groups [lines] into parts of at most about [maxChars] each, never splitting a line,
     * so every message is summarized (a single huge message gets a part of its own).
     */
    fun parts(lines: List<String>, maxChars: Int = PART_CHARS): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + line.length + 1 > maxChars) {
                parts += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }
}
