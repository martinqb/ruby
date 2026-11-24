package com.martin.ruby.voice

/**
 * Turns text into the sounds Kokoro speaks, using its US English dictionary, so Ruby decides
 * what every word sounds like instead of the library (which, since sherpa-onnx 2.0, silently
 * skips any word its dictionary doesn't have, and any word with a quote or bracket stuck to it).
 *
 * For each word, in order:
 *  1. the dictionary;
 *  2. a regular ending on a word the dictionary has: "orbits" (orbit), "noticed" (notice),
 *     "kilometers" (kilometer), "going" (go), and possessives, "Neptune's", which sound like
 *     the plural;
 *  3. the parts of a hyphenated word: "color-enhanced";
 *  4. spelled out, letter by letter: what's left is mostly unusual names.
 *
 * Text should already be through [KokoroText.normalize] (numbers, units and symbols as words).
 * Kept free of Android, for tests. [dictionary] maps lowercase words to their sounds with the
 * spaces removed ("kəlˈɑməɾəɹ"); every sound symbol is one character, one Kokoro token.
 */
class KokoroPhonemizer(private val dictionary: Map<String, String>) {

    /** How one word was found, for tests and logs. */
    enum class Source { DICTIONARY, ENDING, PARTS, SPELLED }

    data class Word(val text: String, val sounds: String, val source: Source)

    /** The sounds for [text]: words separated by spaces, with Kokoro's pause marks kept. */
    fun phonemes(text: String): String = words(text).joinToString("") { it.sounds }

    /**
     * [text] as Kokoro's pieces: each word's sounds (with a space before it unless it follows
     * an opening), and pause marks. Exposed for tests and logs.
     */
    fun words(text: String): List<Word> {
        val out = mutableListOf<Word>()
        var needSpace = false
        for (m in TOKEN.findAll(text.replace('’', '\'').replace('‘', '\''))) {
            val token = m.value
            if (token[0].isLetter()) {
                val w = word(token)
                out += w.copy(sounds = (if (needSpace) " " else "") + w.sounds)
                needSpace = true
            } else {
                // A pause mark goes straight after the word before it, and isn't doubled.
                val mark = PAUSES[token] ?: continue
                if (out.isNotEmpty() && out.last().sounds.lastOrNull()?.toString() in PAUSE_MARKS) continue
                if (out.isEmpty()) continue
                out += Word(token, mark, Source.DICTIONARY)
                needSpace = true
            }
        }
        return out
    }

    /** The sounds of one word (letters, apostrophes and hyphens). */
    fun word(token: String): Word {
        // A trailing apostrophe is either a closing quote ('ices') or a plural possessive
        // (the parents'); neither changes the sound.
        val lower = token.lowercase().trimEnd('\'')
        dictionary[lower]?.let { return Word(token, it, Source.DICTIONARY) }
        ending(lower)?.let { return Word(token, it, Source.ENDING) }
        if ('-' in lower) {
            val parts = lower.split('-').filter { it.isNotEmpty() }
            if (parts.size > 1) {
                val sounds = parts.map { word(it) }
                return Word(token, sounds.joinToString(" ") { it.sounds }, Source.PARTS)
            }
        }
        return Word(token, spell(lower), Source.SPELLED)
    }

    /** A word the dictionary has with a regular ending added, or null. */
    private fun ending(w: String): String? {
        fun base(b: String) = dictionary[b]?.let { split(it) }
        fun first(vararg candidates: String): List<String>? = candidates.firstNotNullOfOrNull { c -> c.takeIf { it.length >= 2 }?.let(::base) }

        // Possessives sound like the plural: Neptune's (and a plural possessive, the
        // parents', arrives here as "parents").
        if (w.endsWith("'s")) {
            val owner = w.dropLast(2)
            return base(owner)?.let { join(KokoroWordForms.plural(it)) } ?: ending(owner + "s")
        }
        if ('\'' in w) return null

        when {
            w.endsWith("ies") -> first(w.dropLast(3) + "y")?.let { return join(KokoroWordForms.plural(it)) }
            w.endsWith("es") -> first(w.dropLast(1), w.dropLast(2))?.let { return join(KokoroWordForms.plural(it)) }
            w.endsWith("s") && !w.endsWith("ss") -> first(w.dropLast(1))?.let { return join(KokoroWordForms.plural(it)) }
        }
        when {
            w.endsWith("ied") -> first(w.dropLast(3) + "y")?.let { return join(KokoroWordForms.past(it)) }
            w.endsWith("ed") -> first(w.dropLast(1), w.dropLast(2), doubled(w.dropLast(2)))?.let { return join(KokoroWordForms.past(it)) }
        }
        when {
            w.endsWith("ying") -> first(w.dropLast(4) + "ie", w.dropLast(3))?.let { return join(KokoroWordForms.ing(it)) }
            w.endsWith("ing") -> first(w.dropLast(3), w.dropLast(3) + "e", doubled(w.dropLast(3)))?.let { return join(KokoroWordForms.ing(it)) }
        }
        return null
    }

    /** "plann" → "plan" (a doubled last letter), else "" (no candidate). */
    private fun doubled(stem: String) =
        if (stem.length >= 3 && stem.last() == stem[stem.length - 2]) stem.dropLast(1) else ""

    /** Letter by letter, each as the dictionary says the letter's name. */
    private fun spell(w: String): String =
        w.filter { it in 'a'..'z' }.map { dictionary[it.toString()] ?: "" }.filter { it.isNotEmpty() }.joinToString(" ")

    private fun split(sounds: String) = sounds.map { it.toString() }
    private fun join(sounds: List<String>) = sounds.joinToString("")

    companion object {
        /** Words (letters, with apostrophes and hyphens inside), or a single other character. */
        private val TOKEN = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*'?|[^\\sA-Za-z]")

        /**
         * Marks Kokoro pauses on. Brackets and dashes become commas; quotes, other symbols and
         * stray characters are dropped (KokoroText has already turned symbols that mean something
         * into words).
         */
        private val PAUSES = mapOf(
            "." to ".", "," to ",", "!" to "!", "?" to "?", ";" to ";", ":" to ":", "…" to "…",
            "—" to "—", "–" to ",", "(" to ",", ")" to ",", "[" to ",", "]" to ",",
        )
        private val PAUSE_MARKS = PAUSES.values.toSet()

        /** Reads the dictionary file: "word s o u n d s" per line; the first entry for a word wins. */
        fun load(lines: Sequence<String>): Map<String, String> {
            val map = HashMap<String, String>(200_000)
            for (line in lines) {
                val space = line.indexOf(' ')
                if (space <= 0) continue
                val word = line.substring(0, space)
                if (word !in map) map[word] = line.substring(space + 1).replace(" ", "")
            }
            return map
        }
    }
}
