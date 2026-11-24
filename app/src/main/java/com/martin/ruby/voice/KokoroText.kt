package com.martin.ruby.voice

/**
 * Gets text ready for Kokoro, which (in sherpa-onnx 2.x) only says words in its dictionary:
 * digits and unknown words are dropped without a sound. So numbers, times, money and dates
 * are written out as words first, and words the dictionary doesn't have are spelled out
 * letter by letter ("GPS" becomes "g p s"). Kept free of Android, for tests.
 */
object KokoroText {

    /** Phrases longer than this are split at a pause (see [phrases]). */
    const val MAX_PHRASE = 60

    /** Pieces shorter than this are joined to the next, so speech doesn't get choppy. */
    const val MIN_PHRASE = 20

    /**
     * Splits a long sentence at its natural pauses (after a comma, semicolon, colon or dash),
     * keeping the punctuation with each piece, into phrases of about [maxLength] characters.
     * Kokoro makes a whole piece before any of it can be played, and only a little faster
     * than it's spoken, so a long sentence after a short one used to leave a silence. Short
     * text is returned as it is.
     */
    fun phrases(text: String, maxLength: Int = MAX_PHRASE, minLength: Int = MIN_PHRASE): List<String> {
        val clean = text.trim()
        if (clean.length <= maxLength) return listOf(clean).filter { it.isNotEmpty() }
        val pieces = Regex("(?<=[,;:—–])\\s+|\\s+(?=[—–]\\s)").split(clean).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length >= minLength && current.length + 1 + piece.length > maxLength) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) {
            // A short tail joins the phrase before it.
            if (current.length < minLength && out.isNotEmpty()) out[out.lastIndex] = out.last() + " " + current
            else out += current.toString()
        }
        return out
    }

    /** [known] says whether the dictionary has a (lowercase) word. */
    fun prepare(text: String, known: (String) -> Boolean): String {
        var s = text.replace('’', '\'').replace('‘', '\'')
        s = expandAbbreviations(s)
        s = expandNumbers(s)
        return spellUnknown(s, known)
    }

    // Dates

    private val MONTHS = mapOf(
        "jan" to "January", "feb" to "February", "mar" to "March", "apr" to "April", "jun" to "June",
        "jul" to "July", "aug" to "August", "sep" to "September", "sept" to "September", "oct" to "October",
        "nov" to "November", "dec" to "December",
    )
    private val WEEKDAYS = mapOf(
        "mon" to "Monday", "tue" to "Tuesday", "tues" to "Tuesday", "wed" to "Wednesday", "thu" to "Thursday",
        "thur" to "Thursday", "thurs" to "Thursday", "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday",
    )

    /** "Nov 24" → "November 24", "Mon, Nov 24" → "Monday, November 24" (only in date-like places). */
    private fun expandAbbreviations(text: String): String {
        var s = Regex("\\b([A-Za-z]{3,4})\\.?(?= \\d)").replace(text) { m ->
            MONTHS[m.groupValues[1].lowercase()] ?: m.value
        }
        s = Regex("\\b([A-Za-z]{3,5})\\.?(?=,)").replace(s) { m ->
            WEEKDAYS[m.groupValues[1].lowercase()] ?: m.value
        }
        return s
    }

    // Numbers

    private fun expandNumbers(text: String): String {
        var s = text
        // Times: "5:00 PM" → "five p m", "5:30pm" → "five thirty p m", "17:05" → "seventeen oh five".
        s = Regex("\\b(\\d{1,2}):(\\d{2})(?:\\s*([AaPp])\\.?\\s*[Mm]\\.?(?![A-Za-z]))?").replace(s) { m ->
            val hour = m.groupValues[1].toLong()
            val minute = m.groupValues[2].toInt()
            val suffix = m.groupValues[3].lowercase()
            val minutes = when {
                minute == 0 && suffix.isEmpty() && hour > 12 -> " hundred"
                minute == 0 -> if (suffix.isEmpty()) " o'clock" else ""
                minute < 10 -> " oh ${words(minute.toLong())}"
                else -> " ${words(minute.toLong())}"
            }
            words(hour) + minutes + if (suffix.isEmpty()) "" else " $suffix m"
        }
        // Money: "$12.50" → "twelve dollars and fifty cents".
        s = Regex("\\$(\\d[\\d,]*)(?:\\.(\\d{2}))?").replace(s) { m ->
            val dollars = m.groupValues[1].replace(",", "").toLong()
            val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0
            val d = words(dollars) + if (dollars == 1L) " dollar" else " dollars"
            if (cents == 0L) d else "$d and ${words(cents)}" + if (cents == 1L) " cent" else " cents"
        }
        // Percentages: "45%" → "45 percent" (the number is written out below).
        s = Regex("(\\d)\\s*%").replace(s) { m -> "${m.groupValues[1]} percent" }
        // Ordinals: "24th" → "twenty fourth".
        s = Regex("\\b(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE).replace(s) { m ->
            m.groupValues[1].toLongOrNull()?.let(::ordinal) ?: m.value
        }
        // Decimals: "3.5" → "three point five".
        s = Regex("\\b(\\d+)\\.(\\d+)\\b").replace(s) { m ->
            words(m.groupValues[1].toLong()) + " point " + m.groupValues[2].map { DIGITS[it - '0'] }.joinToString(" ")
        }
        // Years: "2026" → "twenty twenty six", "1905" → "nineteen oh five".
        s = Regex("(?<![\\d,.])\\b(1[1-9]\\d\\d|20\\d\\d)\\b(?![\\d,.])").replace(s) { m -> year(m.value.toInt()) }
        // Everything else: "1,234" → "one thousand two hundred thirty four"; "-5" → "minus five".
        s = Regex("(?<![\\w.])(-?)(\\d{1,3}(?:,\\d{3})+|\\d+)\\b").replace(s) { m ->
            val digits = m.groupValues[2].replace(",", "")
            val spoken = if (digits.length > 15) digits.map { DIGITS[it - '0'] }.joinToString(" ") else words(digits.toLong())
            (if (m.groupValues[1] == "-") "minus " else "") + spoken
        }
        return s
    }

    private val DIGITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private val TEENS = listOf(
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")

    /** 1234 → "one thousand two hundred thirty four". */
    fun words(n: Long): String {
        if (n < 0) return "minus " + words(-n)
        if (n < 10) return DIGITS[n.toInt()]
        if (n < 20) return TEENS[(n - 10).toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 == 0L) "" else " " + DIGITS[(n % 10).toInt()]
        if (n < 1000) return DIGITS[(n / 100).toInt()] + " hundred" + if (n % 100 == 0L) "" else " " + words(n % 100)
        for ((size, name) in SCALES) {
            if (n >= size) return words(n / size) + " $name" + if (n % size == 0L) "" else " " + words(n % size)
        }
        error("unreachable")
    }

    /** 24 → "twenty fourth". */
    fun ordinal(n: Long): String {
        val w = words(n)
        val last = w.substringAfterLast(' ')
        val head = w.dropLast(last.length)
        val irregular = mapOf(
            "one" to "first", "two" to "second", "three" to "third", "five" to "fifth", "eight" to "eighth",
            "nine" to "ninth", "twelve" to "twelfth",
        )
        val ord = irregular[last] ?: when {
            last.endsWith("y") -> last.dropLast(1) + "ieth"
            else -> last + "th"
        }
        return head + ord
    }

    /** 2026 → "twenty twenty six", 2000 → "two thousand", 2005 → "two thousand five", 1905 → "nineteen oh five". */
    fun year(y: Int): String = when {
        y in 2000..2009 -> words(y.toLong())
        y % 100 == 0 -> words((y / 100).toLong()) + " hundred"
        y % 100 < 10 -> words((y / 100).toLong()) + " oh " + DIGITS[y % 100]
        else -> words((y / 100).toLong()) + " " + words((y % 100).toLong())
    }

    // Unknown words

    private val WORD = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")

    /** Words the dictionary doesn't have are spelled out, so they aren't silently dropped. */
    private fun spellUnknown(text: String, known: (String) -> Boolean): String = WORD.replace(text) { m ->
        val word = m.value
        when {
            known(word.lowercase()) -> word
            // "Martin's": the dictionary may have only "martin".
            word.endsWith("'s", ignoreCase = true) && known(word.dropLast(2).lowercase()) -> word
            // "e-mail", "re-check": say the parts.
            word.contains('-') -> word.split('-').joinToString(" ") { part -> spellIfUnknown(part, known) }
            else -> spell(word)
        }
    }

    private fun spellIfUnknown(word: String, known: (String) -> Boolean) =
        if (known(word.lowercase())) word else spell(word)

    /** "GPS" → "g p s". */
    private fun spell(word: String) = word.filter { it.isLetter() }.lowercase().toList().joinToString(" ")
}
