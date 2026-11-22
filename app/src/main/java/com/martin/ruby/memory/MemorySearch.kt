package com.martin.ruby.memory

/** Turns what the user types into a safe full-text search. Kept separate so it's easy to test. */
object MemorySearch {

    /**
     * Splits [typed] into words and asks for messages containing all of them, matching
     * the start of words ("drive" finds "driving"). Each word is quoted, so characters
     * that mean something to FTS5 (like `"`, `*`, `-` or `OR`) are just searched for as
     * text and can't break the query. Returns null if there's nothing to search for.
     */
    fun ftsQuery(typed: String): String? {
        val words = typed
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .take(MAX_WORDS)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"$it\"*" }
    }

    private const val MAX_WORDS = 10
}
