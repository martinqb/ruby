package com.martin.ruby.ai

import org.json.JSONException
import org.json.JSONObject

/**
 * Reminders in Free mode. Gemini Nano can't use tools, so the phone spots reminder requests,
 * has Nano turn a request into a small JSON answer, and checks that answer itself before doing
 * anything (a real time in the future, a saved place, a reminder that's waiting). Anything it
 * can't do safely, Ruby says so, and offers Claude only if a Claude key is set.
 *
 * Kept free of Android, for tests; the phone side is in [com.martin.ruby.chat.ChatSession].
 */
object FreeReminders {

    enum class Kind { SET, LIST, CANCEL }

    /** What the user seems to want about reminders, or null for an ordinary message. */
    fun kindOf(message: String): Kind? {
        val m = message.lowercase()
        val aboutReminders = REMIND.containsMatchIn(m)
        return when {
            aboutReminders && CANCEL.containsMatchIn(m) -> Kind.CANCEL
            aboutReminders && LIST.containsMatchIn(m) -> Kind.LIST
            SET.containsMatchIn(m) -> Kind.SET
            else -> null
        }
    }

    private val REMIND = Regex("\\bremind(er|ers)?\\b")
    private val SET = Regex("\\bremind me\\b|\\bset (a |an )?reminder\\b|\\bdon'?t let me forget\\b|\\bremind (us|him|her)\\b")
    private val LIST = Regex("\\b(what|which|any|list|show|my|all)\\b.*\\breminders?\\b|\\breminders?\\b.*\\b(set|waiting|have|got)\\b\\??")
    private val CANCEL = Regex("\\b(cancel|delete|remove|clear|drop|forget)\\b")

    /** Gemini Nano's instructions for turning a request into a reminder. */
    fun setPrompt(now: String, places: List<String>) =
        "Turn the user's reminder request into one JSON object and nothing else:\n" +
            "{\"text\": what to remind them of, short, \"at\": local date and time \"YYYY-MM-DDTHH:mm\" or null, " +
            "\"place\": one of the saved places or null, \"sure\": true or false}\n" +
            "Exactly one of \"at\" and \"place\" must be set. Work out \"at\" from the current time: $now. " +
            "Saved places: ${places.joinToString(", ").ifEmpty { "none" }}. Use a place only if it's one of those, " +
            "spelled the same. Set \"sure\" to false if the time or place is unclear (like \"later\" or a place " +
            "that isn't saved)."

    /** Gemini Nano's instructions for picking which waiting reminder to cancel. */
    fun cancelPrompt(pending: List<Pair<Long, String>>) =
        "The user wants to cancel one of their waiting reminders. Answer with one JSON object and nothing else: " +
            "{\"id\": the number of the reminder they mean, or null if it's unclear}. Waiting reminders:\n" +
            pending.joinToString("\n") { (id, text) -> "$id: $text" }

    /** A reminder Gemini Nano made out of a request, before the phone checks it. */
    data class Request(val text: String, val at: String?, val place: String?, val sure: Boolean)

    /** Reads Nano's answer: the first JSON object in it (it often wraps it in a code block). Null if there isn't one. */
    fun json(answer: String): JSONObject? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(answer.substring(start, end + 1))
        } catch (e: JSONException) {
            null
        }
    }

    fun request(answer: String): Request? {
        val o = json(answer) ?: return null
        fun str(key: String) = if (o.isNull(key)) null else o.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
        val text = str("text") ?: return null
        return Request(text, str("at"), str("place"), o.optBoolean("sure", true))
    }

    fun cancelId(answer: String): Long? {
        val o = json(answer) ?: return null
        return if (o.isNull("id")) null else o.optLong("id", -1).takeIf { it > 0 }
    }

    /** What Ruby says when Free mode couldn't do a reminder request. */
    fun cantDo(why: String, claudeOffered: Boolean) =
        "I can't do that one in Free mode: $why" + if (claudeOffered) " Want me to use Claude for this one?" else ""
}
