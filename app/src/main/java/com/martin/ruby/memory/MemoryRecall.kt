package com.martin.ruby.memory

import java.time.Instant
import java.time.LocalDate
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

    /** Summaries are longer than messages; they're cut at this many characters. */
    const val MAX_SUMMARY_CHARS = 1_000

    /** How many of the best-matching messages are always sent word for word, even if their day has a summary. */
    const val KEEP_VERBATIM = 2

    /** How many slots are kept for summaries that matched by themselves. */
    const val SUMMARY_SLOTS = 2

    /** One thing recalled: a past message, or a summary of a past day. */
    sealed interface Recalled {
        data class Message(val message: MemoryMessage) : Recalled
        data class Summary(val summary: DaySummary) : Recalled
    }

    /**
     * Picks what to send, at most [MAX_MEMORIES]. [ranked] are matching messages, best first;
     * [summaryOf] gives a message's day summary, if its day has one; [matchedSummaries] are
     * summaries that matched by themselves, best first.
     *
     * The best [KEEP_VERBATIM] messages go word for word, so exact details aren't lost. A
     * weaker message whose day has a summary is replaced by that summary (once per day), which
     * sends fewer words for the same day. Up to [SUMMARY_SLOTS] places are kept for matching
     * summaries, so they aren't crowded out by many messages.
     */
    fun choose(
        ranked: List<MemoryMessage>,
        summaryOf: (MemoryMessage) -> DaySummary?,
        matchedSummaries: List<DaySummary>,
    ): List<Recalled> {
        val fromMessages = mutableListOf<Recalled>()
        val days = mutableSetOf<String>()
        ranked.forEachIndexed { i, m ->
            val summary = summaryOf(m)
            if (summary == null || i < KEEP_VERBATIM) fromMessages += Recalled.Message(m)
            else if (days.add(summary.day)) fromMessages += Recalled.Summary(summary)
        }
        val extra = matchedSummaries.filter { days.add(it.day) }.map { Recalled.Summary(it) }
        val reserved = minOf(extra.size, SUMMARY_SLOTS)
        val first = fromMessages.take(MAX_MEMORIES - reserved)
        return (first + extra + fromMessages.drop(first.size)).take(MAX_MEMORIES)
    }

    /** As [format], for messages only. */
    fun format(memories: List<MemoryMessage>, zone: ZoneId = ZoneId.systemDefault()): String? =
        formatRecalled(memories.map { Recalled.Message(it) }, zone)

    /**
     * The note added to the newest message, oldest first, e.g. "- Mon, Nov 3, 2026, 8:14 AM,
     * while Driving, the user said: ..." or "- Summary of Tue, Nov 4, 2026: ...". Null if
     * there's nothing.
     */
    fun formatRecalled(items: List<Recalled>, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (items.isEmpty()) return null
        val lines = items.sortedBy { startOf(it, zone) }.joinToString("\n") { item ->
            when (item) {
                is Recalled.Message -> {
                    val m = item.message
                    val time = WHEN.format(Instant.ofEpochMilli(m.timeMillis).atZone(zone))
                    val where = m.context?.let { ", while $it" } ?: ""
                    val who = if (m.role == MemoryMessage.ROLE_RUBY) "you (Ruby) said" else "the user said"
                    "- $time$where, $who: ${shorten(m.text, MAX_CHARS)}"
                }
                is Recalled.Summary ->
                    "- Summary of ${SummaryText.label(item.summary.day)}: ${shorten(item.summary.text, MAX_SUMMARY_CHARS)}"
            }
        }
        return "From Ruby's memory (past messages and day summaries that may be related; use them only if they help):\n$lines"
    }

    private fun startOf(item: Recalled, zone: ZoneId): Long = when (item) {
        is Recalled.Message -> item.message.timeMillis
        is Recalled.Summary -> LocalDate.parse(item.summary.day).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun shorten(text: String, max: Int): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max).trimEnd() + "…"
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
