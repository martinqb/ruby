package com.martin.ruby.reminders

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** Reading and writing reminder times. Kept free of Android so it's easy to test. */
object ReminderText {

    private val SHOWN = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy, h:mm a", Locale.US)

    /** Like "Tue, Nov 24, 2026, 5:00 PM", in the phone's time zone. */
    fun time(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        SHOWN.format(Instant.ofEpochMilli(millis).atZone(zone))

    /** Either the time in millis, or why the text isn't a usable reminder time. */
    sealed interface Parsed {
        data class Ok(val millis: Long) : Parsed
        data class Bad(val why: String) : Parsed
    }

    /**
     * Reads a local date and time like "2026-11-24T17:00" (seconds optional) in [zone].
     * It must be in the future (a minute's slack allowed) and within two years.
     */
    fun parse(text: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Parsed {
        val local = try {
            LocalDateTime.parse(text.trim())
        } catch (e: DateTimeParseException) {
            return Parsed.Bad("\"$text\" isn't a date and time like 2026-11-24T17:00.")
        }
        // In a clock change's gap or overlap, atZone picks the sensible nearby time.
        val millis = local.atZone(zone).toInstant().toEpochMilli()
        return when {
            millis < nowMillis - 60_000 -> Parsed.Bad("${time(millis, zone)} is in the past. It's now ${time(nowMillis, zone)}.")
            millis > nowMillis + 2 * 366L * 24 * 3_600_000 -> Parsed.Bad("That's more than two years away.")
            else -> Parsed.Ok(maxOf(millis, nowMillis))
        }
    }
}
