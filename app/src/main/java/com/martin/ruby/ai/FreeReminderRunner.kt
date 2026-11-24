package com.martin.ruby.ai

import android.content.Context
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.log.SensingLog
import com.martin.ruby.reminders.Reminder
import com.martin.ruby.reminders.ReminderAlarms
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.reminders.ReminderText
import kotlinx.coroutines.flow.first
import java.time.ZoneId

/**
 * Does a reminder request in Free mode: Gemini Nano reads the request (see [FreeReminders]),
 * and the phone checks the result and does it, the same way Claude's reminder tool does
 * (same store, alarms, duplicate check). Listing needs no AI at all.
 */
class FreeReminderRunner(context: Context, private val write: suspend (system: String, prompt: String) -> String) {

    /** What happened: Ruby's words, and the action line under her reply if something was done. */
    data class Outcome(val reply: String, val action: String? = null, val couldNot: String? = null)

    private val app = context.applicationContext
    private val store = ReminderStore(app)

    suspend fun handle(kind: FreeReminders.Kind, message: String, nowMillis: Long = System.currentTimeMillis()): Outcome =
        when (kind) {
            FreeReminders.Kind.LIST -> list()
            FreeReminders.Kind.SET -> set(message, nowMillis)
            FreeReminders.Kind.CANCEL -> cancel(message)
        }

    private suspend fun list(): Outcome {
        val waiting = store.pending().filter { it.state == Reminder.STATE_PENDING }
        if (waiting.isEmpty()) return Outcome("You don't have any reminders waiting.")
        val lines = waiting.joinToString("\n") { r -> "• ${r.text}, " + (r.place?.let { "when you get to $it" } ?: ReminderText.time(r.dueMillis!!)) }
        return Outcome(if (waiting.size == 1) "You have one reminder waiting:\n$lines" else "You have ${waiting.size} reminders waiting:\n$lines")
    }

    private suspend fun set(message: String, nowMillis: Long): Outcome {
        val places = PlaceStore(app).places.first().map { it.name }
        val now = "${ReminderText.time(nowMillis)} (${ZoneId.systemDefault().id})"
        val answer = write(FreeReminders.setPrompt(now, places), message)
        val request = FreeReminders.request(answer) ?: return couldNot("I couldn't make sense of that request.")
        if (!request.sure) return couldNot("the time or place wasn't clear enough.")
        if ((request.at == null) == (request.place == null)) return couldNot("I need either a time or one of your saved places.")

        if (request.place != null) {
            val place = places.firstOrNull { it.equals(request.place, ignoreCase = true) }
                ?: return couldNot("\"${request.place}\" isn't one of your saved places.")
            ReminderText.duplicateOf(store.pending(), request.text, null, place)?.let {
                return Outcome("That one's already set: ${it.text}, when you get to $place.")
            }
            store.add(request.text, dueMillis = null, place = place)
            return Outcome(
                "Done. I'll remind you to ${request.text.replaceFirstChar { it.lowercase() }} when you get to $place.",
                action = "Reminder set: ${request.text}, when you get to $place",
            )
        }
        return when (val parsed = ReminderText.parse(request.at!!, nowMillis)) {
            is ReminderText.Parsed.Bad -> couldNot(parsed.why)
            is ReminderText.Parsed.Ok -> {
                ReminderText.duplicateOf(store.pending(), request.text, parsed.millis, null)?.let {
                    return Outcome("That one's already set: ${it.text}, ${ReminderText.time(it.dueMillis!!)}.")
                }
                store.add(request.text, dueMillis = parsed.millis, place = null)
                val time = ReminderText.time(parsed.millis)
                val late = if (ReminderAlarms.canBeExact(app)) "" else " Exact timing is off on this phone, so it may be a few minutes late."
                Outcome(
                    "Done. I'll remind you to ${request.text.replaceFirstChar { it.lowercase() }}, $time.$late",
                    action = "Reminder set: ${request.text}, $time",
                )
            }
        }
    }

    private suspend fun cancel(message: String): Outcome {
        val waiting = store.pending().filter { it.state == Reminder.STATE_PENDING }
        if (waiting.isEmpty()) return Outcome("You don't have any reminders waiting.")
        val answer = write(FreeReminders.cancelPrompt(waiting.map { it.id to it.text }), message)
        val id = FreeReminders.cancelId(answer) ?: return couldNot("I couldn't tell which reminder you meant.")
        val reminder = waiting.firstOrNull { it.id == id } ?: return couldNot("I couldn't tell which reminder you meant.")
        if (!store.cancel(id)) return couldNot("that reminder wasn't waiting any more.")
        return Outcome("Cancelled: ${reminder.text}.", action = "Reminder cancelled: ${reminder.text}")
    }

    private fun couldNot(why: String): Outcome {
        SensingLog.add("Free mode reminder: couldn't do it ($why)")
        return Outcome("", couldNot = why)
    }
}
