package com.martin.ruby.voice

/**
 * Splits a reply into sentences while it streams in, so Ruby can start speaking the first
 * sentence before the rest has arrived. Feed it the whole reply so far each time.
 */
class SpeechChunker(private val maxChars: Int = 300) {

    /** How much of the reply has been handed out to speak. */
    private var used = 0
    private var latest = ""

    /** The complete sentences that weren't in earlier calls. */
    fun feed(soFar: String): List<String> {
        latest = soFar
        val pending = soFar.substring(used.coerceAtMost(soFar.length))
        var end = BOUNDARY.findAll(pending).lastOrNull()?.range?.last?.plus(1) ?: 0
        // A long run with no sentence end (a list, a run-on): break at a space instead.
        if (pending.length - end > maxChars) {
            val space = pending.lastIndexOf(' ', end + maxChars)
            end = if (space > end) space + 1 else end + maxChars
        }
        if (end == 0) return emptyList()
        used += end
        return split(pending.substring(0, end))
    }

    /** Whatever's left once the reply is complete. */
    fun finish(): List<String> {
        val rest = latest.substring(used.coerceAtMost(latest.length))
        used = latest.length
        return split(rest)
    }

    private fun split(text: String): List<String> =
        text.split(BOUNDARY_KEEP).map { it.trim() }.filter { it.any(Char::isLetterOrDigit) }

    private companion object {
        /** A sentence end (., !, ?, … with any closing quote or bracket) then space, or a line break. */
        val BOUNDARY = Regex("[.!?…]+[\"'”’)\\]]*\\s+|\\n+")

        /** The same, splitting just after the punctuation so it stays with its sentence. */
        val BOUNDARY_KEEP = Regex("(?<=[.!?…][\"'”’)\\]]?)\\s+|\\n+")
    }
}
