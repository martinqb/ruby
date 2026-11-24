package com.martin.ruby.voice

import com.martin.ruby.voice.KokoroPhonemizer.Source
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How words become Kokoro's sounds. A small stand-in for the dictionary, with real entries
 * copied from it; the device test runs the real one.
 */
class KokoroPhonemizerTest {

    private val dictionary = mapOf(
        "kilometer" to "kəlˈɑməɾəɹ",
        "notice" to "nˈOɾəs",
        "orbit" to "ˈɔɹbət",
        "discover" to "dəskˈʌvəɹ",
        "suggest" to "səɡʤˈɛst",
        "visitor" to "vˈɪzəɾəɹ",
        "finish" to "fˈɪnəʃ",
        "want" to "wˈɑnt",
        "neptune" to "nˈɛptˌun",
        "ices" to "ˈIsᵻz",
        "plan" to "plˈæn",
        "charge" to "ʧˈɑɹʤ",
        "go" to "ɡˈO",
        "color" to "kˈʌləɹ",
        "enhance" to "ɛnhˈæns",
        "try" to "tɹˈI",
        "the" to "ðə",
        "is" to "ˈɪz",
        "far" to "fˈɑɹ",
        "g" to "ʤˈi", "p" to "pˈi", "s" to "ˈɛs", "u" to "jˈu", "r" to "ˈɑɹ", "b" to "bˈi",
        "a" to "ˈA", "i" to "ˈI", "n" to "ˈɛn",
    )
    private val phonemizer = KokoroPhonemizer(dictionary)

    private fun word(w: String) = phonemizer.word(w)

    @Test
    fun dictionaryWords_comeStraightFromIt() {
        assertEquals(KokoroPhonemizer.Word("Neptune", "nˈɛptˌun", Source.DICTIONARY), word("Neptune"))
        assertEquals(Source.DICTIONARY, word("ices").source)
    }

    @Test
    fun missingEndings_areMadeFromTheBaseWord() {
        // The words the Neptune reply spelled out letter by letter.
        assertEquals(KokoroPhonemizer.Word("kilometers", "kəlˈɑməɾəɹz", Source.ENDING), word("kilometers"))
        assertEquals("nˈOɾəst", word("noticed").sounds) // voiceless: t
        assertEquals("ˈɔɹbəts", word("orbits").sounds) // voiceless: s
        assertEquals("dəskˈʌvəɹd", word("Discovered").sounds) // voiced: d
        assertEquals("səɡʤˈɛsts", word("suggests").sounds)
        assertEquals("vˈɪzəɾəɹz", word("Visitors").sounds)
        // -es after a hissing sound, -ed after t, doubled letters, dropped e, y to i.
        assertEquals("fˈɪnəʃᵻz", word("finishes").sounds)
        assertEquals("wˈɑntᵻd", word("wanted").sounds)
        assertEquals("plˈænd", word("planned").sounds)
        assertEquals("plˈænɪŋ", word("planning").sounds)
        assertEquals("ʧˈɑɹʤɪŋ", word("charging").sounds)
        assertEquals("ɡˈOɪŋ", word("going").sounds)
        assertEquals("tɹˈIz", word("tries").sounds)
        assertEquals("tɹˈId", word("tried").sounds)
    }

    @Test
    fun possessivesSoundLikeThePlural() {
        assertEquals(KokoroPhonemizer.Word("Neptune's", "nˈɛptˌunz", Source.ENDING), word("Neptune's"))
        // A closing quote or plural possessive apostrophe doesn't change the sound.
        assertEquals("ˈIsᵻz", word("ices'").sounds)
    }

    @Test
    fun hyphenatedWords_areSaidInParts() {
        assertEquals(KokoroPhonemizer.Word("color-enhanced", "kˈʌləɹ ɛnhˈænst", Source.PARTS), word("color-enhanced"))
    }

    @Test
    fun whatsLeft_isSpelled() {
        assertEquals(KokoroPhonemizer.Word("GPS", "ʤˈi pˈi ˈɛs", Source.SPELLED), word("GPS"))
    }

    @Test
    fun aPhrase_keepsPausesAndDropsQuotesAndBrackets() {
        assertEquals("ðə ˈIsᵻz ˈɪz fˈɑɹ.", phonemizer.phonemes("the \"ices\" is far."))
        // Brackets become a pause, as a comma would.
        assertEquals("nˈɛptˌun, ðə ˈIsᵻz, ˈɪz fˈɑɹ.", phonemizer.phonemes("Neptune (the ices) is far."))
        // Nothing that isn't a pause is passed on.
        assertEquals("ðə fˈɑɹ", phonemizer.phonemes("the * far @"))
    }

    @Test
    fun loadingTheDictionary_firstEntryWins() {
        val loaded = KokoroPhonemizer.load(sequenceOf("read ɹ ˈ i d", "read ɹ ˈ ɛ d", "go ɡ ˈ O", "bad-line"))
        assertEquals(mapOf("read" to "ɹˈid", "go" to "ɡˈO"), loaded)
    }
}
