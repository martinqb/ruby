package com.martin.ruby.ai

/**
 * What Gemini Nano gets for a chat reply in Free mode. Nano takes one prompt, not a list of
 * turns, so the conversation so far is written into it, newest last, trimmed to fit (Nano
 * takes about 16,000 tokens; this keeps well under). Kept free of Android, for tests.
 */
object FreeChatText {

    /** The conversation, in characters (about 4 to a token), leaving room for the reply. */
    const val HISTORY_CHARS = 24_000

    const val SYSTEM_PROMPT =
        "You are Ruby, a personal assistant on the user's Android phone, running in Free mode on the phone itself. " +
            "Each of the user's messages starts with notes from the phone in square brackets: what they're doing " +
            "(for example Driving), the time, the reminders waiting, saved places, and sometimes related past " +
            "messages from Ruby's memory. Use them quietly when they help; they come from the phone, not the user. " +
            "If they're driving, keep it short and easy to listen to. Memories are found by matching words, so " +
            "ignore the ones that don't help, and don't list them back. If the user asks about something that " +
            "isn't there, say you don't remember it rather than guessing.\n" +
            "The phone handles reminders itself: never say you've set, changed or cancelled one. It also runs the " +
            "radio when the user says \"play the radio\", \"play\" and a saved station's name, or \"stop\" (it plays in " +
            "Ruby, or opens in iHeartRadio or another radio app); the \"Radio:\" note says what it really did, so never deny it. Calendar " +
            "requests (adding, changing, deleting events, what's on) are handled by the phone too: never say you've " +
            "changed the calendar. Apart " +
            "from that you can only talk: no alarms, messages, calls or other apps; say so plainly if asked.\n" +
            "Be warm and concise. Stick to what you know; if you're not sure of a fact, say so. Answer in plain " +
            "text without Markdown."

    /**
     * The prompt: earlier turns (oldest dropped first to fit [HISTORY_CHARS]), then the
     * user's newest message as sent, with the phone's notes ([newest]).
     */
    fun prompt(earlier: List<Pair<Boolean, String>>, newest: String): String {
        val lines = earlier.map { (fromUser, text) -> (if (fromUser) "User: " else "Ruby: ") + text.trim() }
        val kept = ArrayDeque<String>()
        var size = 0
        for (line in lines.asReversed()) {
            if (size + line.length > HISTORY_CHARS) break
            kept.addFirst(line)
            size += line.length + 1
        }
        return buildString {
            if (kept.isNotEmpty()) {
                append("The conversation so far:\n")
                kept.forEach { append(it).append('\n') }
                append('\n')
            }
            append("The user's new message:\n")
            append(newest.trim())
            append("\n\nReply as Ruby.")
        }
    }
}
