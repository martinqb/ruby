package com.martin.ruby.voice

/**
 * The sounds of regular English endings, added to a base word's sounds. Kokoro's dictionary has
 * base words but often not their endings: "kilometer" but not "kilometers", "notice" but not
 * "noticed", "orbit" but not "orbits". [KokoroPhonemizer] finds the base word and adds the
 * ending with these rules. (Making every missing form up front instead came to 485,000 extra
 * entries and about 250 MB more memory on the phone, so they're made only as words come up.)
 *
 * Kept free of Android, for tests. Sounds are lists of one-character symbols, as in the
 * dictionary; stress marks (ˈ ˌ) are symbols of their own.
 */
object KokoroWordForms {

    private val VOICELESS = setOf("p", "t", "k", "f", "θ")
    private val SIBILANTS = setOf("s", "z", "ʃ", "ʒ", "ʧ", "ʤ")
    private val STRESS = setOf("ˈ", "ˌ")

    private fun lastSound(sounds: List<String>) = sounds.lastOrNull { it !in STRESS } ?: ""

    /** "-s" (and possessive "'s"): "s" after a voiceless sound (orbits), "ᵻz" after a hissing one (finishes), else "z" (kilometers). */
    fun plural(sounds: List<String>): List<String> = when (lastSound(sounds)) {
        in SIBILANTS -> sounds + listOf("ᵻ", "z")
        in VOICELESS -> sounds + "s"
        else -> sounds + "z"
    }

    /** "-ed": "ᵻd" after t or d (wanted), "t" after a voiceless sound (noticed), else "d" (discovered). */
    fun past(sounds: List<String>): List<String> = when (lastSound(sounds)) {
        "t", "d" -> sounds + listOf("ᵻ", "d")
        in VOICELESS, "s", "ʃ", "ʧ" -> sounds + "t"
        else -> sounds + "d"
    }

    /** "-ing". */
    fun ing(sounds: List<String>): List<String> = sounds + listOf("ɪ", "ŋ")
}
