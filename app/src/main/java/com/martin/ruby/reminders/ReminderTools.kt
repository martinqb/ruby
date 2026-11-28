package com.martin.ruby.reminders

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.martin.ruby.chat.ToolBox
import com.martin.ruby.chat.ToolOutcome
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.flow.first

/**
 * The reminder tools Claude can call: set, list and cancel.
 *
 * The tool definitions ([DEFINITIONS]) are fixed text, the same in every request. Claude's
 * earlier thinking is only valid against the exact tools it was made with, so anything that
 * changes (like the saved places) must not go in them: the places go in each message's
 * note instead, and [run] checks against the places as they are when it runs.
 */
class ReminderTools(context: Context) : ToolBox {

    private val appContext = context.applicationContext
    private val store = ReminderStore(appContext)

    override val tools: List<BetaTool> get() = DEFINITIONS

    override suspend fun run(name: String, input: Map<*, *>): ToolOutcome = try {
        when (name) {
            SET -> set(input)
            LIST -> list()
            CANCEL -> cancel(input)
            else -> ToolOutcome("There's no tool called $name.", isError = true)
        }
    } catch (e: Exception) {
        SensingLog.add("Tool $name failed: ${e.message}")
        ToolOutcome("That didn't work on the phone: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private suspend fun set(input: Map<*, *>): ToolOutcome {
        val text = (input["text"] as? String)?.trim().orEmpty()
        val at = (input["at"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val placeAsked = (input["place"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        if (text.isEmpty()) return ToolOutcome("`text` is missing.", isError = true)
        if ((at == null) == (placeAsked == null)) return ToolOutcome("Give either `at` or `place`, not both or neither.", isError = true)

        if (placeAsked != null) {
            // The places as they are now, not when the conversation started.
            val savedPlaces = PlaceStore(appContext).places.first().map { it.name }
            val place = savedPlaces.firstOrNull { it.equals(placeAsked, ignoreCase = true) }
                ?: return ToolOutcome(
                    "\"$placeAsked\" isn't a saved place. Saved places: " +
                        (savedPlaces.joinToString(", ").ifEmpty { "none yet (the user can add them on the Places screen)" }) + ".",
                    isError = true,
                )
            alreadySet(text, dueMillis = null, place = place)?.let { return it }
            val r = store.add(text, dueMillis = null, place = place)
            return ToolOutcome(
                "Reminder ${r.id} set for the next time they arrive at $place: $text.",
                action = "Reminder set: $text, when you get to $place",
            )
        }

        return when (val parsed = ReminderText.parse(at!!, System.currentTimeMillis())) {
            is ReminderText.Parsed.Bad -> ToolOutcome(parsed.why, isError = true)
            is ReminderText.Parsed.Ok -> {
                alreadySet(text, dueMillis = parsed.millis, place = null)?.let { return it }
                val r = store.add(text, dueMillis = parsed.millis, place = null)
                val time = ReminderText.time(parsed.millis)
                ToolOutcome(
                    "Reminder ${r.id} set for $time: $text." +
                        if (ReminderAlarms.canBeExact(appContext)) "" else
                            " Exact timing is off on this phone, so it may be a few minutes late.",
                    action = "Reminder set: $text, $time",
                )
            }
        }
    }

    /**
     * If the same reminder is already waiting, says so instead of making a second one. Not an
     * error: the user's reminder exists, which is what they asked for.
     */
    private suspend fun alreadySet(text: String, dueMillis: Long?, place: String?): ToolOutcome? {
        val existing = ReminderText.duplicateOf(store.pending(), text, dueMillis, place) ?: return null
        val whenText = existing.place?.let { "for the next time they arrive at $it" }
            ?: "for ${ReminderText.time(existing.dueMillis!!)}"
        SensingLog.add("Reminder ${existing.id} was already set; didn't make a duplicate.")
        return ToolOutcome("Reminder ${existing.id} is already set $whenText: ${existing.text}. No duplicate was made.")
    }

    private suspend fun list(): ToolOutcome {
        val pending = store.pending()
        if (pending.isEmpty()) return ToolOutcome("No reminders are waiting.")
        return ToolOutcome(pending.joinToString("\n") { r ->
            val whenText = r.place?.let { "on arriving at $it" } ?: ReminderText.time(r.dueMillis!!)
            "${r.id}: ${r.text} ($whenText)"
        })
    }

    private suspend fun cancel(input: Map<*, *>): ToolOutcome {
        val id = (input["id"] as? Number)?.toLong() ?: return ToolOutcome("`id` must be a number.", isError = true)
        val reminder = store.get(id) ?: return ToolOutcome("There's no reminder $id.", isError = true)
        return if (store.cancel(id)) {
            ToolOutcome("Reminder $id cancelled: ${reminder.text}.", action = "Reminder cancelled: ${reminder.text}")
        } else {
            ToolOutcome("Reminder $id wasn't waiting (it's ${reminder.state}).", isError = true)
        }
    }

    companion object {
        const val SET = "set_reminder"
        const val LIST = "list_reminders"
        const val CANCEL = "cancel_reminder"

        /**
         * The tool definitions: fixed text, built once, identical in every request. Never put
         * anything that can change (places, the time, the user's state) in here.
         */
        val DEFINITIONS: List<BetaTool> by lazy { SPECS.map { tool(it.name, it.description, it.properties, it.required) } }

        /**
         * The same tools as plain data, for other AI models (OpenAI-style function tools), so
         * Claude's and theirs never drift apart.
         */
        val SPECS: List<ToolSpec> = listOf(
            ToolSpec(
                SET,
                "Set a reminder for the user. Give either `at` (a time) or `place` (arriving at a saved " +
                    "place), not both. Ruby shows it as a notification then.",
                mapOf(
                    "text" to mapOf(
                        "type" to "string",
                        "description" to "What to remind them of, short and in their words, e.g. \"Call Mom\".",
                    ),
                    "at" to mapOf(
                        "type" to "string",
                        "description" to "Local date and time in the phone's time zone, as YYYY-MM-DDTHH:MM, e.g. " +
                            "2026-11-24T17:00. Work it out from the current time in the user's message.",
                    ),
                    "place" to mapOf(
                        "type" to "string",
                        "description" to "The name of one of the user's saved places, as listed in the " +
                            "\"Saved places\" note on their newest message. Fires the next time they arrive there.",
                    ),
                ),
                required = listOf("text"),
            ),
            ToolSpec(LIST, "List the reminders that haven't gone off yet, with their ids.", emptyMap(), required = emptyList()),
            ToolSpec(
                CANCEL,
                "Cancel a reminder by id (from list_reminders), for example when the user no longer needs it.",
                mapOf("id" to mapOf("type" to "integer", "description" to "The reminder's id.")),
                required = listOf("id"),
            ),
        )

        private fun tool(name: String, description: String, properties: Map<String, Any>, required: List<String>): BetaTool =
            BetaTool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    BetaTool.InputSchema.builder()
                        .properties(
                            BetaTool.InputSchema.Properties.builder().apply {
                                properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) }
                            }.build()
                        )
                        .required(required)
                        .build()
                )
                // Inputs stream as they're written; run() checks every field itself.
                .eagerInputStreaming(true)
                .build()
    }
}

/** A tool as plain data: its name, description, JSON-schema properties and required fields. */
data class ToolSpec(val name: String, val description: String, val properties: Map<String, Any>, val required: List<String>)
