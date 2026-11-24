package com.martin.ruby.voice

/**
 * Gets text ready for Kokoro: [normalize] writes out what isn't plain words (numbers, times,
 * money, dates, units, symbols, ranges, abbreviations) and removes what shouldn't be said
 * (markdown, quotes), and [phrases] cuts long sentences into pieces Kokoro can make quickly.
 * Turning the words into sounds is [KokoroPhonemizer]'s job. Kept free of Android, for tests.
 */
object KokoroText {

    /** Phrases longer than this are split at a pause (see [phrases]). */
    const val MAX_PHRASE = 60

    /** Pieces shorter than this are joined to the next, so speech doesn't get choppy. */
    const val MIN_PHRASE = 20

    /** Pieces longer than this, with no pause to split at, are cut at a word (see [phrases]). */
    const val LONG_PHRASE = 80

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
        // A long stretch with no pause in it would still take too long to make (the sentence
        // after "Kuiper Belt" left a two-second silence), so cut it near the middle, before a
        // joining word if there's one.
        return out.flatMap { splitLong(it, minLength) }
    }

    /** How long the first pieces of a stretch of speech may be, so she starts talking soon. */
    val LEAD_IN = listOf(30, 45)

    /**
     * Cuts the start of [phrases] into short pieces ([LEAD_IN]: about 30, then 45 characters)
     * so the first sound comes quickly: Kokoro makes a whole piece before any of it plays, and
     * on the phone that takes about 0.9 s per second of speech. The pieces then grow, so the
     * player keeps getting further ahead instead of the silence just moving later. Cuts are
     * made at a pause or before a joining word if there's one, else at a space.
     */
    fun leadIn(phrases: List<String>): List<String> {
        val queue = ArrayDeque(phrases)
        val out = mutableListOf<String>()
        for (limit in LEAD_IN) {
            val next = queue.removeFirstOrNull() ?: break
            if (next.length <= limit) {
                out += next
                continue
            }
            val cut = cutNear(next, limit)
            if (cut == null) {
                out += next
            } else {
                out += next.substring(0, cut).trim()
                queue.addFirst(next.substring(cut).trim())
            }
        }
        return out + queue
    }

    /** Where to cut [text] to keep the first part within [limit]: after a pause, before a joining word, or at a space. */
    private fun cutNear(text: String, limit: Int): Int? {
        val minimum = 8
        val spaces = text.indices.filter { text[it] == ' ' && it in minimum..limit }
        if (spaces.isEmpty()) return null
        val afterPause = spaces.filter { text[it - 1] in ",;:—–" }
        val beforeJoining = spaces.filter { i -> text.substring(i + 1).substringBefore(' ').lowercase().trimEnd(',', '.') in JOINING_WORDS }
        return (afterPause.ifEmpty { beforeJoining }.ifEmpty { spaces }).max()
    }

    /** Words a long stretch is best cut before: they start a new part of the sentence. */
    private val JOINING_WORDS = setOf(
        "and", "but", "or", "so", "because", "which", "that", "who", "when", "while", "where",
        "with", "without", "than", "after", "before", "since", "until", "unless", "though",
        "although", "then", "from", "into", "about",
    )

    private fun splitLong(text: String, minLength: Int): List<String> {
        if (text.length <= LONG_PHRASE) return listOf(text)
        val middle = text.length / 2
        // Every space with enough on both sides; the best is before a joining word, nearest the middle.
        val spaces = text.indices.filter { text[it] == ' ' && it >= minLength && text.length - it - 1 >= minLength }
        if (spaces.isEmpty()) return listOf(text)
        val beforeJoining = spaces.filter { i ->
            text.substring(i + 1).substringBefore(' ').lowercase().trimEnd(',', '.') in JOINING_WORDS
        }
        val cut = beforeJoining.ifEmpty { spaces }.minBy { kotlin.math.abs(it - middle) }
        return splitLong(text.substring(0, cut), minLength) + splitLong(text.substring(cut + 1), minLength)
    }

    /**
     * [text] with everything that isn't plain words written out as words, and markdown and
     * quotes removed, ready for [KokoroPhonemizer].
     */
    fun normalize(text: String): String {
        var s = text.replace('’', '\'').replace('‘', '\'')
        s = cleanUp(s)
        s = expandUnits(s)
        s = expandSymbols(s)
        s = expandAbbreviations(s)
        s = expandRanges(s)
        s = expandNumbers(s)
        return s.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    // Markdown and quotes

    private fun cleanUp(text: String): String {
        var s = text
        s = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(s) { it.groupValues[1] } // [text](link) → text
        s = Regex("(?m)^\\s*#{1,6}\\s+").replace(s, "") // headings
        s = Regex("(?m)^\\s*[-*•]\\s+").replace(s, "") // bullet points
        s = s.replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
        s = Regex("(?<=^|\\s)[*_](?=\\S)|(?<=\\S)[*_](?=\\s|$|[.,!?;:])").replace(s, "") // *word*, _word_
        s = s.replace("\"", "").replace("“", "").replace("”", "") // quotes aren't said
        return s
    }

    // Units, after a number

    private class Unit(val one: String, val many: String)

    private val UNITS = mapOf(
        "km/h" to Unit("kilometer per hour", "kilometers per hour"),
        "kph" to Unit("kilometer per hour", "kilometers per hour"),
        "mph" to Unit("mile per hour", "miles per hour"),
        "km" to Unit("kilometer", "kilometers"),
        "mi" to Unit("mile", "miles"),
        "m" to Unit("meter", "meters"),
        "cm" to Unit("centimeter", "centimeters"),
        "mm" to Unit("millimeter", "millimeters"),
        "kg" to Unit("kilogram", "kilograms"),
        "g" to Unit("gram", "grams"),
        "lb" to Unit("pound", "pounds"),
        "lbs" to Unit("pound", "pounds"),
        "oz" to Unit("ounce", "ounces"),
        "ml" to Unit("milliliter", "milliliters"),
        "l" to Unit("liter", "liters"),
        "gal" to Unit("gallon", "gallons"),
        "ft" to Unit("foot", "feet"),
        "°f" to Unit("degree Fahrenheit", "degrees Fahrenheit"),
        "°c" to Unit("degree Celsius", "degrees Celsius"),
        "°" to Unit("degree", "degrees"),
        // The dictionary has "psi" as the Greek letter ("sigh"); tyre pressure is said as letters.
        "psi" to Unit("P S I", "P S I"),
        "h" to Unit("hour", "hours"),
        "hr" to Unit("hour", "hours"),
        "hrs" to Unit("hour", "hours"),
        "min" to Unit("minute", "minutes"),
        "mins" to Unit("minute", "minutes"),
        "sec" to Unit("second", "seconds"),
        "secs" to Unit("second", "seconds"),
    )

    /** One-letter units only count in their usual case: 5 m (meters) but not 5 M (million). */
    private val SINGLE_LETTER_CASE = mapOf("m" to setOf("m"), "g" to setOf("g"), "h" to setOf("h"), "l" to setOf("l", "L"))

    private val UNIT_AFTER_NUMBER: Regex by lazy {
        val names = UNITS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it).replace("°", "°\\E\\s?\\Q") }
        Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s?($names)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
    }

    /**
     * "5 km" → "5 kilometers", "1 km" → "1 kilometer", "72°F" → "72 degrees Fahrenheit". Only
     * right after a number, so ordinary words are left alone ("in" isn't treated as inches:
     * "at 5 in the morning" is far likelier).
     */
    private fun expandUnits(text: String): String = UNIT_AFTER_NUMBER.replace(text) { m ->
        val written = m.groupValues[2]
        val key = written.lowercase().replace(Regex("\\s"), "")
        val unit = UNITS[key] ?: return@replace m.value
        SINGLE_LETTER_CASE[key]?.let { allowed -> if (written !in allowed) return@replace m.value }
        val number = m.groupValues[1]
        "$number " + if (number.replace(",", "").toDoubleOrNull() == 1.0) unit.one else unit.many
    }

    // Symbols

    private fun expandSymbols(text: String): String {
        var s = text
        s = s.replace("&", " and ")
        s = s.replace("×", " times ")
        s = Regex("#\\s?(?=\\d)").replace(s, "number ")
        s = Regex("~\\s?(?=\\d)").replace(s, "about ")
        s = Regex("(?<=[\\s\\d])\\+(?=[\\s\\d])").replace(s, " plus ")
        s = Regex("(?<=[\\s\\d])=(?=[\\s\\d])").replace(s, " equals ")
        s = Regex("\\s@\\s").replace(s, " at ")
        s = Regex("\\band/or\\b", RegexOption.IGNORE_CASE).replace(s, "and or")
        s = Regex("(?<=[A-Za-z])/(?=[A-Za-z])").replace(s, " or ") // walk/drive → walk or drive
        return s
    }

    // Ranges

    /** "5–7" or "5-7" → "5 to 7" (not dates like 2026-11-24, or negative numbers). */
    private fun expandRanges(text: String): String =
        Regex("(?<![\\d,.\\-–])(\\d+(?:\\.\\d+)?)\\s*[–—-]\\s*(\\d+(?:\\.\\d+)?)(?![\\d.\\-–])").replace(text) { m ->
            "${m.groupValues[1]} to ${m.groupValues[2]}"
        }

    // Dates and abbreviations

    private val MONTHS = mapOf(
        "jan" to "January", "feb" to "February", "mar" to "March", "apr" to "April", "jun" to "June",
        "jul" to "July", "aug" to "August", "sep" to "September", "sept" to "September", "oct" to "October",
        "nov" to "November", "dec" to "December",
    )
    private val WEEKDAYS = mapOf(
        "mon" to "Monday", "tue" to "Tuesday", "tues" to "Tuesday", "wed" to "Wednesday", "thu" to "Thursday",
        "thur" to "Thursday", "thurs" to "Thursday", "fri" to "Friday", "sat" to "Saturday", "sun" to "Sunday",
    )

    /**
     * "Nov 24" → "November 24", "Mon, Nov 24" → "Monday, November 24" (only in date-like places),
     * and a few abbreviations that are only written: e.g., i.e., etc., vs, approx.
     */
    private fun expandAbbreviations(text: String): String {
        var s = Regex("\\b([A-Za-z]{3,4})\\.?(?= \\d)").replace(text) { m ->
            MONTHS[m.groupValues[1].lowercase()] ?: m.value
        }
        s = Regex("\\b([A-Za-z]{3,5})\\.?(?=,)").replace(s) { m ->
            WEEKDAYS[m.groupValues[1].lowercase()] ?: m.value
        }
        s = Regex("\\be\\.g\\.(?=\\s|,|$)", RegexOption.IGNORE_CASE).replace(s, "for example")
        s = Regex("\\bi\\.e\\.(?=\\s|,|$)", RegexOption.IGNORE_CASE).replace(s, "that is")
        s = Regex("\\betc\\.", RegexOption.IGNORE_CASE).replace(s, "et cetera.")
        s = Regex("\\bvs\\.?(?=\\s)", RegexOption.IGNORE_CASE).replace(s, "versus")
        s = Regex("\\bapprox\\.?(?=\\s)", RegexOption.IGNORE_CASE).replace(s, "approximately")
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
}
