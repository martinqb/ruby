package com.martin.ruby.calendar

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.martin.ruby.chat.ToolBox
import com.martin.ruby.chat.ToolOutcome
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds calendar actions from what an AI asked for, checking everything: shared by Claude's
 * tools and Free mode. Nothing here saves; it only makes the action Ruby then asks about.
 */
class CalendarHelper(context: Context, private val zone: ZoneId = ZoneId.systemDefault()) {

    val store = CalendarStore(context)

    /** A new event, or the reason it can't be made (for the user or the AI). */
    suspend fun add(title: String, date: String, time: String?, minutes: Int?, calendar: String?, location: String?): Result<CalendarAction> = runCatching {
        require(title.isNotBlank()) { "The event needs a title." }
        val calendars = store.calendars()
        if (calendars.isEmpty()) throw IllegalArgumentException("There's no calendar on this phone Ruby can add to.")
        val cal = CalendarText.pick(calendar, calendars, store.defaultId())
            ?: throw IllegalArgumentException("\"$calendar\" isn't one of the calendars on this phone (${calendars.joinToString(", ") { it.label }}).")
        val (start, end, allDay) = CalendarText.times(date, time, minutes, zone)
        CalendarAction.Add(EventDraft(title.trim(), start, end, allDay, cal.id, location?.trim()?.ifEmpty { null }))
    }

    /** A change to event [id]: only the parts given change. */
    suspend fun change(id: Long, title: String?, date: String?, time: String?, minutes: Int?, calendar: String?): Result<CalendarAction> = runCatching {
        val event = find(id)
        val calendars = store.calendars()
        val calId = if (calendar.isNullOrBlank()) event.calendarId else (CalendarText.pick(calendar, calendars, null)?.id
            ?: throw IllegalArgumentException("\"$calendar\" isn't one of the calendars on this phone."))
        val startLocal = java.time.Instant.ofEpochMilli(event.startMillis).atZone(if (event.allDay) java.time.ZoneOffset.UTC else zone)
        val oldMinutes = ((event.endMillis - event.startMillis) / 60_000).toInt()
        val (start, end, allDay) = if (date == null && time == null && minutes == null) {
            Triple(event.startMillis, event.endMillis, event.allDay)
        } else {
            CalendarText.times(
                date ?: startLocal.toLocalDate().toString(),
                time ?: if (event.allDay) null else startLocal.toLocalTime().toString().take(5),
                minutes ?: oldMinutes,
                zone,
            )
        }
        CalendarAction.Change(event, EventDraft(title?.trim()?.ifEmpty { null } ?: event.title, start, end, allDay, calId, event.location))
    }

    suspend fun delete(id: Long): Result<CalendarAction> = runCatching { CalendarAction.Delete(find(id)) }

    /** Events from [from] to [to] (dates, inclusive), in the phone's time zone. */
    suspend fun between(from: LocalDate, to: LocalDate): List<CalEvent> =
        store.events(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

    /** The coming weeks' events, for picking one to change or delete. */
    suspend fun upcoming(): List<CalEvent> = between(LocalDate.now(zone).minusDays(1), LocalDate.now(zone).plusDays(WINDOW_DAYS)).take(60)

    private suspend fun find(id: Long): CalEvent =
        between(LocalDate.now(zone).minusDays(60), LocalDate.now(zone).plusDays(400)).firstOrNull { it.eventId == id }
            ?: throw IllegalArgumentException("There's no event $id on the calendar.")

    companion object {
        const val WINDOW_DAYS = 45L
    }
}

/**
 * Claude's calendar tools. They read events, and *propose* additions, changes and deletions:
 * [propose] makes Ruby ask the user, and the phone saves only when they say yes.
 */
class CalendarTools(context: Context, private val propose: suspend (CalendarAction) -> String) : ToolBox {

    private val helper = CalendarHelper(context)
    private val zone = ZoneId.systemDefault()

    override val tools: List<BetaTool> get() = DEFINITIONS

    override suspend fun run(name: String, input: Map<*, *>): ToolOutcome = try {
        if (!helper.store.canRead()) {
            ToolOutcome("Ruby isn't allowed to read the calendar yet. Tell the user to allow calendar access (Ruby asks when they tap Allow calendar).", isError = true)
        } else when (name) {
            EVENTS -> events(input)
            ADD -> proposal(helper.add(str(input, "title").orEmpty(), str(input, "date").orEmpty(), str(input, "time"), int(input, "minutes"), str(input, "calendar"), str(input, "location")))
            CHANGE -> proposal(helper.change(long(input, "event_id") ?: -1, str(input, "title"), str(input, "date"), str(input, "time"), int(input, "minutes"), str(input, "calendar")))
            DELETE -> proposal(helper.delete(long(input, "event_id") ?: -1))
            else -> ToolOutcome("There's no tool called $name.", isError = true)
        }
    } catch (e: Exception) {
        ToolOutcome("That didn't work on the phone: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private suspend fun events(input: Map<*, *>): ToolOutcome {
        val from = LocalDate.parse(str(input, "from") ?: return ToolOutcome("`from` is missing.", isError = true))
        val to = str(input, "to")?.let(LocalDate::parse) ?: from
        val calendars = helper.store.calendars()
        val events = helper.between(from, to)
        if (events.isEmpty()) return ToolOutcome("No events from $from to $to.")
        return ToolOutcome(events.joinToString("\n") { e ->
            "${e.eventId}: ${e.title}, ${CalendarText.whenText(e.startMillis, e.endMillis, e.allDay, zone)}" +
                (calendars.firstOrNull { it.id == e.calendarId }?.let { " (${it.label})" } ?: "") + (e.location?.let { ", at $it" } ?: "")
        })
    }

    private suspend fun proposal(result: Result<CalendarAction>): ToolOutcome = result.fold(
        onSuccess = { action ->
            val question = propose(action)
            ToolOutcome(
                "Not saved yet: the phone saves it only when the user says yes. Ask them with exactly this question and " +
                    "nothing else: \"$question\"",
            )
        },
        onFailure = { ToolOutcome(it.message ?: "That can't be done.", isError = true) },
    )

    private fun str(input: Map<*, *>, key: String) = (input[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    private fun int(input: Map<*, *>, key: String) = (input[key] as? Number)?.toInt()
    private fun long(input: Map<*, *>, key: String) = (input[key] as? Number)?.toLong()

    companion object {
        const val EVENTS = "calendar_events"
        const val ADD = "propose_calendar_event"
        const val CHANGE = "propose_calendar_change"
        const val DELETE = "propose_calendar_delete"

        /** Fixed text, identical in every request (see ReminderTools.DEFINITIONS). */
        val DEFINITIONS: List<BetaTool> by lazy {
            val date = mapOf("type" to "string", "description" to "A date, YYYY-MM-DD, worked out from the current time in the user's message.")
            listOf(
                tool(EVENTS, "List the events on the user's calendars between two dates (inclusive), with their ids.",
                    mapOf("from" to date, "to" to date), listOf("from")),
                tool(ADD, "Propose adding an event to one of the user's calendars. Nothing is saved: the phone asks the user and saves only if they say yes. Use it whenever they ask to add something to their calendar.",
                    mapOf(
                        "title" to mapOf("type" to "string", "description" to "A short title in their words, e.g. \"Dentist\"."),
                        "date" to date,
                        "time" to mapOf("type" to "string", "description" to "Start time, HH:MM (24-hour). Leave out for an all-day event."),
                        "minutes" to mapOf("type" to "integer", "description" to "How long, in minutes (default 60)."),
                        "calendar" to mapOf("type" to "string", "description" to "Which calendar, if they said (e.g. \"Samsung\", \"Google\"), as in the \"Calendars\" note. Leave out for their default."),
                        "location" to mapOf("type" to "string", "description" to "Where, if they said."),
                    ), listOf("title", "date")),
                tool(CHANGE, "Propose changing an event (by id from calendar_events): only the fields given change. Nothing is saved until the user says yes.",
                    mapOf(
                        "event_id" to mapOf("type" to "integer", "description" to "The event's id."),
                        "title" to mapOf("type" to "string", "description" to "New title."),
                        "date" to date,
                        "time" to mapOf("type" to "string", "description" to "New start time, HH:MM."),
                        "minutes" to mapOf("type" to "integer", "description" to "New length in minutes."),
                        "calendar" to mapOf("type" to "string", "description" to "Move it to this calendar."),
                    ), listOf("event_id")),
                tool(DELETE, "Propose deleting an event (by id from calendar_events). Nothing is deleted until the user says yes.",
                    mapOf("event_id" to mapOf("type" to "integer", "description" to "The event's id.")), listOf("event_id")),
            )
        }

        private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String>): BetaTool =
            BetaTool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    BetaTool.InputSchema.builder()
                        .properties(BetaTool.InputSchema.Properties.builder().apply {
                            properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) }
                        }.build())
                        .required(required)
                        .build()
                )
                .eagerInputStreaming(true)
                .build()
    }
}

/** Several tool boxes as one: each tool runs in the box that has it. */
class ToolBoxes(private vararg val boxes: ToolBox) : ToolBox {
    override val tools: List<BetaTool> get() = boxes.flatMap { it.tools }
    override suspend fun run(name: String, input: Map<*, *>): ToolOutcome =
        boxes.firstOrNull { b -> b.tools.any { it.name() == name } }?.run(name, input)
            ?: ToolOutcome("There's no tool called $name.", isError = true)
}
