package com.martin.ruby.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Finding the few past messages worth sending along with a new chat message (Phase 6
 * step 3, "send only what's relevant"). Kept free of Android so it's easy to test.
 *
 * This version matches keywords with the full-text index. Finding memories by meaning
 * (on-device embeddings) can replace [ftsQuery] later without changing the rest.
 */
object MemoryRecall {

    /** How many memories go with each message. */
    const val MAX_MEMORIES = 5

    /** Longer memories are cut to this many characters. */
    const val MAX_CHARS = 500

    /**
     * A full-text query for messages containing any of the meaningful words in [message],
     * matching the start of words like the search box does. Common words ("what", "did",
     * "the") are left out, since nearly every message has them. Null if nothing's left.
     */
    fun ftsQuery(message: String): String? {
        val words = message
            .lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            // Short words say little, but numbers ("36 psi", "5") can matter.
            .filter { it.length >= 3 || (it.isNotEmpty() && it.all(Char::isDigit)) }
            .filter { it !in COMMON_WORDS }
            .distinct()
            .take(MAX_WORDS)
        if (words.isEmpty()) return null
        return words.joinToString(" OR ") { "\"$it\"*" }
    }

    /**
     * The note added to the newest message, listing [memories] oldest first, e.g.
     * "Mon, Nov 3, 2026, 8:14 AM, while Driving, you said: ...". Null if there are none.
     */
    fun format(memories: List<MemoryMessage>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (memories.isEmpty()) return null
        val lines = memories.sortedBy { it.timeMillis }.joinToString("\n") { m ->
            val time = WHEN.format(Instant.ofEpochMilli(m.timeMillis).atZone(zone))
            val where = m.context?.let { ", while $it" } ?: ""
            val who = if (m.role == MemoryMessage.ROLE_RUBY) "you (Ruby) said" else "the user said"
            "- $time$where, $who: ${shorten(m.text)}"
        }
        return "From Ruby's memory (past messages that may be related; use them only if they help):\n$lines"
    }

    private fun shorten(text: String): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= MAX_CHARS) oneLine else oneLine.take(MAX_CHARS).trimEnd() + "…"
    }

    private const val MAX_WORDS = 12

    private val WHEN = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy, h:mm a", Locale.US)

    /** Words too common to say anything about what a message is about. */
    private val COMMON_WORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "your", "yours", "all", "any", "can",
        "had", "has", "have", "her", "him", "his", "she", "they", "them", "their", "our", "ours",
        "was", "were", "been", "being", "one", "out", "get", "got", "how", "what", "when", "where",
        "which", "who", "whom", "why", "this", "that", "these", "those", "there", "here", "then",
        "than", "with", "from", "into", "onto", "about", "over", "under", "again", "just", "also",
        "very", "too", "much", "many", "some", "more", "most", "other", "such", "only", "own",
        "same", "will", "would", "shall", "should", "could", "might", "must", "did", "does", "doing",
        "done", "let", "lets", "its", "it's", "isn", "aren", "wasn", "don", "didn", "doesn", "won",
        "can't", "cannot", "yes", "yeah", "okay", "please", "thanks", "thank", "hey", "hello",
        "ruby", "tell", "told", "say", "said", "know", "remember", "think", "want", "need", "like",
        "make", "made", "now", "today", "something", "anything", "thing", "things", "really",
        "myself", "yourself", "after", "before", "while", "because", "until", "each", "every",
    )
}
