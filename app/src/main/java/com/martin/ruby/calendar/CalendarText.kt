package com.martin.ruby.calendar

import com.martin.ruby.ai.FreeReminders
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A calendar on the phone that Ruby can write to. */
data class PhoneCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val primary: Boolean = false,
) {
    /** How Ruby names it: "Google Calendar (martin@gmail.com)", "Samsung Calendar", "My calendar (on this phone)". */
    val label: String
        get() = when {
            accountType == "com.google" -> "Google Calendar ($account)"
            accountType.contains("osp") || accountType.contains("samsung", ignoreCase = true) -> "Samsung Calendar" +
                if (name.isNotBlank() && !name.equals("My calendar", true) && !name.equals(account, true)) " ($name)" else ""
            accountType == "LOCAL" || accountType.contains("local", ignoreCase = true) -> "$name (on this phone)"
            else -> name.ifBlank { account }
        }
}

/** An event already on a calendar. */
data class CalEvent(
    val eventId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarId: Long,
    val location: String? = null,
)

/** A new or changed event's details. All-day events start at midnight UTC, as calendars store them. */
data class EventDraft(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarId: Long,
    val location: String? = null,
)

/** What Ruby has proposed and is waiting for a yes on. Nothing is saved until then. */
sealed interface CalendarAction {
    data class Add(val draft: EventDraft) : CalendarAction
    data class Change(val event: CalEvent, val draft: EventDraft) : CalendarAction
    data class Delete(val event: CalEvent) : CalendarAction
}

/** The calendar's words and checks. Kept free of Android, for tests. */
object CalendarText {

    private val DAY = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** "Tuesday, December 2 at 9:30 AM to 10:30 AM", or "Tuesday, December 2, all day". */
    fun whenText(start: Long, end: Long, allDay: Boolean, zone: ZoneId): String {
        if (allDay) {
            val first = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
            return if (last <= first) "${DAY.format(first)}, all day" else "${DAY.format(first)} to ${DAY.format(last)}, all day"
        }
        val s = Instant.ofEpochMilli(start).atZone(zone)
        val e = Instant.ofEpochMilli(end).atZone(zone)
        val endText = if (e.toLocalDate() == s.toLocalDate()) TIME.format(e) else "${DAY.format(e)} at ${TIME.format(e)}"
        return "${DAY.format(s)} at ${TIME.format(s)} to $endText"
    }

    private fun calendarName(id: Long, calendars: List<PhoneCalendar>) =
        calendars.firstOrNull { it.id == id }?.label ?: "your calendar"

    /** The question Ruby asks (aloud, if she's speaking) before doing anything. */
    fun confirm(action: CalendarAction, calendars: List<PhoneCalendar>, zone: ZoneId): String = when (action) {
        is CalendarAction.Add -> with(action.draft) {
            "$title, ${whenText(startMillis, endMillis, allDay, zone)}" + (location?.let { ", at $it" } ?: "") +
                ", on ${calendarName(calendarId, calendars)}. Shall I save it?"
        }
        is CalendarAction.Change -> "Change ${action.event.title}, ${whenText(action.event.startMillis, action.event.endMillis, action.event.allDay, zone)}, " +
            "to ${action.draft.title}, ${whenText(action.draft.startMillis, action.draft.endMillis, action.draft.allDay, zone)}" +
            (if (action.draft.calendarId != action.event.calendarId) ", on ${calendarName(action.draft.calendarId, calendars)}" else "") +
            "? Shall I save the change?"
        is CalendarAction.Delete -> "Delete ${action.event.title}, ${whenText(action.event.startMillis, action.event.endMillis, action.event.allDay, zone)}, " +
            "from ${calendarName(action.event.calendarId, calendars)}?"
    }

    /** What Ruby says once it's done. */
    fun done(action: CalendarAction, calendars: List<PhoneCalendar>, zone: ZoneId): String = when (action) {
        is CalendarAction.Add -> "Saved: ${action.draft.title}, ${whenText(action.draft.startMillis, action.draft.endMillis, action.draft.allDay, zone)}, on ${calendarName(action.draft.calendarId, calendars)}."
        is CalendarAction.Change -> "Changed: ${action.draft.title}, ${whenText(action.draft.startMillis, action.draft.endMillis, action.draft.allDay, zone)}."
        is CalendarAction.Delete -> "Deleted: ${action.event.title}."
    }

    /** The line under Ruby's reply in the chat. */
    fun actionLine(action: CalendarAction): String = when (action) {
        is CalendarAction.Add -> "Calendar: added ${action.draft.title}"
        is CalendarAction.Change -> "Calendar: changed ${action.draft.title}"
        is CalendarAction.Delete -> "Calendar: deleted ${action.event.title}"
    }

    private val YES = Regex("^(yes|yeah|yep|yup|sure|ok|okay|save it|save|do it|go ahead|please do|please|correct|that's right|that is right|confirm|sounds good|perfect|delete it|change it)( please| thanks| thank you| ruby)?$")
    private val NO = Regex("^(no|nope|cancel|cancel it|don't|do not|never ?mind|stop|forget it|not now|no thanks|no thank you)( please| thanks| ruby)?$")

    private fun clean(message: String) = message.lowercase().trim().trimEnd('.', '!', '?', ',').replace(Regex("\\s+"), " ")

    /** "Yes", "save it", "go ahead"... to Ruby's question. */
    fun isYes(message: String) = YES.matches(clean(message))

    /** "No", "cancel", "never mind"... */
    fun isNo(message: String) = NO.matches(clean(message))

    /**
     * The calendar to use: the one named in [asked] (by its label, name or account, e.g.
     * "Samsung" or "Google"), else the user's chosen default, else the primary Google one, else
     * the first. Null if there are none, or if [asked] names one that isn't there.
     */
    fun pick(asked: String?, calendars: List<PhoneCalendar>, defaultId: Long?): PhoneCalendar? {
        if (calendars.isEmpty()) return null
        if (!asked.isNullOrBlank()) {
            val a = asked.lowercase()
            return calendars.firstOrNull { c -> listOf(c.label, c.name, c.account).any { it.isNotBlank() && (a in it.lowercase() || it.lowercase() in a) } }
                ?: calendars.firstOrNull { c -> ("samsung" in a && "Samsung" in c.label) || ("google" in a && c.accountType == "com.google") || ("phone" in a && "on this phone" in c.label) }
        }
        return calendars.firstOrNull { it.id == defaultId }
            ?: calendars.firstOrNull { it.primary && it.accountType == "com.google" }
            ?: calendars.firstOrNull { it.accountType == "com.google" }
            ?: calendars.first()
    }

    /** The calendars for the AI's note: "Calendars: Google Calendar (a@b.com) [default]; Samsung Calendar." */
    fun note(calendars: List<PhoneCalendar>, defaultId: Long?): String? {
        if (calendars.isEmpty()) return null
        val default = pick(null, calendars, defaultId)?.id
        return "Calendars: " + calendars.joinToString("; ") { it.label + if (it.id == default) " [default]" else "" } + "."
    }

    /** Events for a day or range, as Ruby says them. */
    fun listing(events: List<CalEvent>, what: String, calendars: List<PhoneCalendar>, zone: ZoneId): String {
        if (events.isEmpty()) return "Nothing on your calendar $what."
        val lines = events.sortedWith(compareBy({ !it.allDay }, { it.startMillis })).joinToString("\n") { e ->
            "• ${e.title}, " + whenText(e.startMillis, e.endMillis, e.allDay, zone).substringAfter(" at ").let { t ->
                if (e.allDay) "all day" else t
            } + (calendars.firstOrNull { it.id == e.calendarId }?.let { if (calendars.size > 1) " (${it.label})" else "" } ?: "")
        }
        return (if (events.size == 1) "One thing on your calendar $what:\n" else "${events.size} things on your calendar $what:\n") + lines
    }

    /**
     * An event's start and end from the parts an AI gave: a [date] ("2026-12-02"), an optional
     * [time] ("09:30"; none means all day) and [minutes] (default an hour). Throws [IllegalArgumentException]
     * with a reason the user can read.
     */
    fun times(date: String, time: String?, minutes: Int?, zone: ZoneId): Triple<Long, Long, Boolean> {
        val day = try {
            LocalDate.parse(date.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("\"$date\" isn't a date.")
        }
        if (time.isNullOrBlank()) {
            val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val days = ((minutes ?: 0) / (24 * 60)).coerceAtLeast(1)
            return Triple(start, day.plusDays(days.toLong()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true)
        }
        val t = try {
            LocalTime.parse(time.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("\"$time\" isn't a time.")
        }
        val start = LocalDateTime.of(day, t).atZone(zone)
        val length = (minutes ?: 60).coerceIn(5, 24 * 60)
        return Triple(start.toInstant().toEpochMilli(), start.plus(Duration.ofMinutes(length.toLong())).toInstant().toEpochMilli(), false)
    }

    // Free mode: Gemini Nano reads the request; the phone checks the answer.

    enum class Kind { ADD, LIST, CHANGE, DELETE }

    private val CALENDARISH = Regex("\\b(calendars?|appointments?|meetings?|events?|schedule|agenda|plans)\\b")

    /** What the user wants of the calendar, or null for an ordinary message (or a reminder). */
    fun kindOf(message: String): Kind? {
        val m = message.lowercase()
        if (!CALENDARISH.containsMatchIn(m)) return null
        return when {
            Regex("\\b(delete|remove|cancel|clear|take off)\\b").containsMatchIn(m) -> Kind.DELETE
            Regex("\\b(move|reschedule|change|push|shift|rename|update)\\b").containsMatchIn(m) -> Kind.CHANGE
            // Questions first, so "what's my schedule" isn't taken as scheduling something.
            Regex("^(what|what's|whats|do i|am i|anything|any|show|list|read|tell me|check|is there|are there|when)\\b").containsMatchIn(m) -> Kind.LIST
            Regex("\\b(add|put|schedule|book|create|make|set up|new)\\b").containsMatchIn(m) -> Kind.ADD
            Regex("\\b(busy|free|anything)\\b").containsMatchIn(m) -> Kind.LIST
            else -> null
        }
    }

    fun addPrompt(now: String, calendars: List<PhoneCalendar>) =
        "Turn the user's request into one JSON object and nothing else:\n" +
            "{\"title\": short event title, \"date\": \"YYYY-MM-DD\", \"time\": \"HH:mm\" 24-hour or null for all day, " +
            "\"minutes\": length in minutes or null, \"calendar\": which calendar if they named one, else null, " +
            "\"location\": a place if they gave one, else null, \"sure\": true or false}\n" +
            "Work dates out from now: $now. The calendars: ${calendars.joinToString(", ") { it.label }}. " +
            "Set \"sure\" to false if the date or time is unclear."

    fun listPrompt(now: String) =
        "Which days does the user ask about? Answer with one JSON object and nothing else: " +
            "{\"from\": \"YYYY-MM-DD\", \"to\": \"YYYY-MM-DD\"} (the same day twice for one day). Now: $now."

    fun pickPrompt(now: String, events: List<CalEvent>, change: Boolean, zone: ZoneId) =
        "The user wants to ${if (change) "change" else "delete"} one of these calendar events. Answer with one JSON object and " +
            "nothing else: " + (if (change) "{\"id\": the event's number, \"title\": new title or null, \"date\": new \"YYYY-MM-DD\" or null, " +
            "\"time\": new \"HH:mm\" or null, \"minutes\": new length or null}" else "{\"id\": the event's number}") +
            ", or {\"id\": null} if it's unclear. Now: $now. Events:\n" +
            events.joinToString("\n") { "${it.eventId}: ${it.title}, ${whenText(it.startMillis, it.endMillis, it.allDay, zone)}" }

    /** A new event Nano read from a request, before the phone checks it. */
    data class AddRequest(val title: String, val date: String, val time: String?, val minutes: Int?, val calendar: String?, val location: String?, val sure: Boolean)

    fun addRequest(answer: String): AddRequest? {
        val o = FreeReminders.json(answer) ?: return null
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        return AddRequest(
            title = str("title") ?: return null,
            date = str("date") ?: return null,
            time = str("time"),
            minutes = if (o.isNull("minutes")) null else o.optInt("minutes").takeIf { it > 0 },
            calendar = str("calendar"),
            location = str("location"),
            sure = o.optBoolean("sure", true),
        )
    }

    fun range(answer: String): Pair<String, String>? {
        val o = FreeReminders.json(answer) ?: return null
        val from = o.optString("from").trim().takeIf { it.length == 10 } ?: return null
        val to = o.optString("to").trim().takeIf { it.length == 10 } ?: from
        return from to to
    }

    data class Pick(val id: Long, val title: String?, val date: String?, val time: String?, val minutes: Int?)

    fun pick(answer: String): Pick? {
        val o = FreeReminders.json(answer) ?: return null
        if (o.isNull("id")) return null
        val id = o.optLong("id", -1).takeIf { it > 0 } ?: return null
        fun str(k: String) = if (o.isNull(k)) null else o.optString(k).trim().takeIf { it.isNotEmpty() && it != "null" }
        return Pick(id, str("title"), str("date"), str("time"), if (o.isNull("minutes")) null else o.optInt("minutes").takeIf { it > 0 })
    }
}
