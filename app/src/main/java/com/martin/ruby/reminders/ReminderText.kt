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

    /** At most this many reminders go in [snapshot]; the rest are counted. */
    const val SNAPSHOT_MAX = 20

    /**
     * The reminders waiting right now, for the note on each chat message, e.g.
     * "Waiting reminders: 3: Check the oven (Mon, Nov 23, 2026, 1:25 PM); 4: Buy milk (on
     * arriving at Store)". Read fresh for every message, so it's never out of date the way
     * earlier tool results in the conversation can be.
     */
    fun snapshot(pending: List<Reminder>, zone: ZoneId = ZoneId.systemDefault()): String {
        val waiting = pending.filter { it.state == Reminder.STATE_PENDING }
        if (waiting.isEmpty()) return "Waiting reminders: none."
        val shown = waiting.take(SNAPSHOT_MAX).joinToString("; ") { r ->
            val whenText = r.place?.let { "on arriving at $it" } ?: time(r.dueMillis!!, zone)
            "${r.id}: ${r.text.replace(Regex("\\s+"), " ").trim()} ($whenText)"
        }
        val more = waiting.size - SNAPSHOT_MAX
        return "Waiting reminders: $shown" + if (more > 0) "; and $more more (use list_reminders)." else "."
    }

    /** Two time reminders this close together count as the same time. */
    const val SAME_TIME_MILLIS = 2 * 60_000L

    /**
     * A waiting reminder in [pending] that's the same as a new one would be: the same text
     * (ignoring case, spacing and a final period) and the same place, or a time within
     * [SAME_TIME_MILLIS]. Null if there's none.
     */
    fun duplicateOf(pending: List<Reminder>, text: String, dueMillis: Long?, place: String?): Reminder? {
        fun norm(s: String) = s.trim().trimEnd('.', '!').replace(Regex("\\s+"), " ").lowercase()
        val wanted = norm(text)
        return pending.firstOrNull { r ->
            r.state == Reminder.STATE_PENDING && norm(r.text) == wanted && when {
                place != null -> r.place.equals(place, ignoreCase = true)
                dueMillis != null -> r.dueMillis != null && kotlin.math.abs(r.dueMillis - dueMillis) <= SAME_TIME_MILLIS
                else -> false
            }
        }
    }

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
