package com.martin.ruby.listening

/*
 * The rules behind Ruby's listening modes, kept free of Android so they can be unit tested:
 * the wake phrase, telling the user from Ruby's own voice, the conversation loop, and what the
 * microphone should be doing at any moment.
 */

/** The listening choices on the Voice settings page. They combine freely. */
data class ListeningPrefs(
    /** The quick on/off (for battery): off pauses every mode below, which stay ticked. */
    val on: Boolean = true,
    /** After Ruby finishes speaking, the mic opens again by itself. */
    val conversation: Boolean = false,
    /** Listens on the phone for [wakePhrase]. */
    val wakeWord: Boolean = false,
    val wakePhrase: String = WakePhrase.DEFAULT,
    /** The user can talk over Ruby, and she stops mid-sentence. */
    val fullDuplex: Boolean = false,
) {
    /** Any mode ticked (whether or not listening is switched on). */
    val anyMode: Boolean get() = conversation || wakeWord || fullDuplex

    /** Listening is switched on and there's something to do. */
    val active: Boolean get() = on && anyMode

    /** The wake word and talking over Ruby both need the on-phone listening model (Vosk). */
    val needsModel: Boolean get() = wakeWord || fullDuplex
}

/** The wake phrase: written as the user likes, matched as plain lowercase words. */
object WakePhrase {
    const val DEFAULT = "Hey Ruby"
    const val MAX_WORDS = 4
    private const val MIN_LETTERS = 4

    /** Lowercase words of letters and apostrophes, single spaces: "Hey, Ruby!" is "hey ruby". */
    fun normalize(text: String): String =
        text.lowercase().replace('’', '\'').replace(Regex("[^a-z' ]"), " ")
            .split(' ').map { it.trim('\'') }.filter { it.isNotEmpty() }.joinToString(" ")

    fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Why [text] can't be the wake phrase, written for the user, or null if it's fine. */
    fun problem(text: String): String? {
        val words = words(text)
        return when {
            words.isEmpty() -> "Type a wake word, like \"Hey Ruby\"."
            text.any { it.isDigit() } -> "Use letters only (write numbers as words)."
            words.size > MAX_WORDS -> "Keep it to $MAX_WORDS words or fewer."
            words.sumOf { it.length } < MIN_LETTERS -> "Use something longer, like \"Hey Ruby\", so she isn't woken by accident."
            else -> null
        }
    }

    /**
     * The words in [text] the listening model doesn't know (it can only hear words it knows).
     * [known] looks a word up in the model.
     */
    fun unknownWords(text: String, known: (String) -> Boolean): List<String> = words(text).distinct().filterNot(known)

    /** The Vosk grammar: just the phrase, and "[unk]" so other speech doesn't get forced into it. */
    fun grammar(text: String): String = "[\"" + normalize(text) + "\", \"[unk]\"]"

    /** Whether [heard] contains the phrase, as whole words in order ("ok hey ruby" counts, "hey rubies" doesn't). */
    fun heard(phrase: String, heard: String): Boolean {
        val want = words(phrase)
        val got = words(heard)
        if (want.isEmpty() || got.size < want.size) return false
        return (0..got.size - want.size).any { i -> got.subList(i, i + want.size) == want }
    }
}

/**
 * Talking over Ruby: is what the mic heard while she speaks the user, or her own voice (or the
 * radio) leaking past the echo cancellation? Echo comes back as her own words, so heard words
 * that she's saying right now don't count.
 */
object BargeIn {
    /** Words that stop her even on their own. */
    private val INTERRUPTS = setOf("stop", "wait", "ruby", "hold", "pause", "quiet", "enough", "no")

    fun isUser(heard: String, rubySaying: String): Boolean {
        val words = WakePhrase.words(heard)
        if (words.isEmpty()) return false
        val hers = WakePhrase.words(rubySaying).toSet()
        if (words.size == 1) return words[0] in INTERRUPTS && words[0] !in hers
        val fresh = words.filter { it !in hers }
        // Two or more of the user's own words, and most of what was heard isn't hers.
        return fresh.size >= 2 && fresh.size * 2 >= words.size
    }
}

/** Conversation mode: when the mic opens again by itself, and when the conversation is over. */
object ConversationRules {
    private val ENDINGS = setOf(
        "stop listening", "that's all", "thats all", "that is all", "that'll be all", "never mind", "nevermind",
        "goodbye", "bye", "bye ruby", "goodbye ruby", "we're done", "i'm done", "no thanks", "nothing", "nothing else",
        "end conversation", "stop conversation",
    )
    private val POLITE = Regex("^(ok(ay)? |thanks |thank you |no )+|( thanks| thank you| ruby)+$")

    /** "That's all, thanks" ends the conversation (it isn't sent to the AI). */
    fun endsConversation(text: String): Boolean {
        val n = WakePhrase.normalize(text)
        return n in ENDINGS || POLITE.replace(n, "").trim() in ENDINGS
    }

    /**
     * Whether to open the mic again now: conversation mode is on, the user's last turn was
     * spoken, and Ruby is done (not answering, not speaking, not already listening).
     */
    fun reopen(conversation: Boolean, lastTurnSpoken: Boolean, answering: Boolean, speaking: Boolean, listening: Boolean): Boolean =
        conversation && lastTurnSpoken && !answering && !speaking && !listening
}

/** What the microphone is doing for the listening modes (the Talk button's listening is separate). */
enum class MicUse {
    /** Not listening. */
    NONE,
    /** Listening for the wake phrase only. */
    WAKE,
    /** Ruby is talking: listening, with echo cancellation, for the user talking over her. */
    BARGE_IN,
}

object ListeningPlan {

    /**
     * What the mic should do. [ready] means the mic is allowed and the listening model is
     * installed. [userListening] means the Talk button's recognizer has the mic, so the modes
     * step aside (Android gives the mic to one listener at a time).
     */
    fun micUse(prefs: ListeningPrefs, ready: Boolean, speaking: Boolean, userListening: Boolean): MicUse = when {
        !prefs.active || !ready || userListening -> MicUse.NONE
        speaking && prefs.fullDuplex -> MicUse.BARGE_IN
        // The wake phrase also works while she talks: saying it stops her and she listens.
        prefs.wakeWord -> MicUse.WAKE
        else -> MicUse.NONE
    }

    /** The background-listening notification's text. */
    fun notification(prefs: ListeningPrefs): String {
        val parts = buildList {
            if (prefs.wakeWord) add("listening for \"${prefs.wakePhrase.trim()}\"")
            if (prefs.conversation) add("conversation mode")
            if (prefs.fullDuplex) add("you can talk over her")
        }
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}
