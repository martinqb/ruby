package com.martin.ruby.reminders

import android.content.Context
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaTool
import com.martin.ruby.chat.ToolBox
import com.martin.ruby.chat.ToolOutcome
import com.martin.ruby.log.SensingLog

/**
 * The reminder tools Claude can call: set, list and cancel. [savedPlaces] are the names
 * of the user's saved places, the only places an arrival reminder can use.
 */
class ReminderTools(context: Context, private val savedPlaces: List<String>) : ToolBox {

    private val appContext = context.applicationContext
    private val store = ReminderStore(appContext)

    override val tools: List<BetaTool> = listOf(
        tool(
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
                    "description" to if (savedPlaces.isEmpty()) {
                        "A saved place's name. The user has no saved places yet, so this can't be used."
                    } else {
                        "One of the user's saved places: ${savedPlaces.joinToString(", ")}. Fires when they arrive."
                    },
                ),
            ),
            required = listOf("text"),
        ),
        tool(LIST, "List the reminders that haven't gone off yet, with their ids.", emptyMap(), required = emptyList()),
        tool(
            CANCEL,
            "Cancel a reminder by id (from list_reminders), for example when the user no longer needs it.",
            mapOf("id" to mapOf("type" to "integer", "description" to "The reminder's id.")),
            required = listOf("id"),
        ),
    )

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
            val place = savedPlaces.firstOrNull { it.equals(placeAsked, ignoreCase = true) }
                ?: return ToolOutcome(
                    "\"$placeAsked\" isn't a saved place. Saved places: " +
                        (savedPlaces.joinToString(", ").ifEmpty { "none yet (the user can add them on the Places screen)" }) + ".",
                    isError = true,
                )
            val r = store.add(text, dueMillis = null, place = place)
            return ToolOutcome(
                "Reminder ${r.id} set for the next time they arrive at $place: $text.",
                action = "Reminder set: $text, when you get to $place",
            )
        }

        return when (val parsed = ReminderText.parse(at!!, System.currentTimeMillis())) {
            is ReminderText.Parsed.Bad -> ToolOutcome(parsed.why, isError = true)
            is ReminderText.Parsed.Ok -> {
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

    companion object {
        const val SET = "set_reminder"
        const val LIST = "list_reminders"
        const val CANCEL = "cancel_reminder"
    }
}
