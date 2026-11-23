package com.martin.ruby.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/** Kokoro drops digits and unknown words; [KokoroText] makes sure they're said. */
class KokoroTextTest {

    /** A small stand-in for the 178,000-word dictionary. */
    private val dictionary = setOf(
        "your", "tyre", "check", "is", "at", "on", "the", "it's", "degrees", "outside", "psi", "pressure",
        "martin", "call", "mom", "email", "mail", "street", "elm", "steps", "and", "a", "m", "p", "o'clock",
        "re", "point", "percent", "dollars", "cents", "budget", "thousand", "hello", "how", "are", "you",
    ) + ('a'..'z').map { it.toString() }

    private fun say(text: String) = KokoroText.prepare(text) { it in dictionary || it.all(Char::isLetter) && it in NUMBER_WORDS }

    @Test
    fun times() {
        assertEquals("Call Mom at five p m", say("Call Mom at 5:00 PM"))
        assertEquals("five thirty a m", say("5:30am"))
        assertEquals("nine oh five", say("9:05"))
        assertEquals("nine o'clock", say("9:00"))
        assertEquals("seventeen hundred", say("17:00"))
    }

    @Test
    fun numbersOfEveryKind() {
        assertEquals("Tyre pressure is thirty six psi", say("Tyre pressure is 36 psi"))
        assertEquals("one thousand two hundred thirty four steps", say("1,234 steps"))
        assertEquals("three point five", say("3.5"))
        assertEquals("minus five degrees", say("-5 degrees"))
        assertEquals("forty five percent", say("45%"))
        assertEquals("twelve dollars and fifty cents", say("\$12.50"))
        assertEquals("one dollar", say("\$1"))
        assertEquals("on the twenty fourth", say("on the 24th"))
        assertEquals("first, second, third, twelfth, twentieth, one hundred first", say("1st, 2nd, 3rd, 12th, 20th, 101st"))
    }

    @Test
    fun yearsAndDates() {
        assertEquals("twenty twenty six", say("2026"))
        assertEquals("two thousand", say("2000"))
        assertEquals("two thousand five", say("2005"))
        assertEquals("nineteen oh five", say("1905"))
        assertEquals("Monday, November twenty four, twenty twenty six", say("Mon, Nov 24, 2026"))
        // Not a date: left alone (and spelled, since "nov" alone isn't a word here).
        assertEquals("Elm Street", say("Elm Street"))
    }

    @Test
    fun unknownWords_areSpelledOut_notDropped() {
        assertEquals("Hello z y x how are you", say("Hello Zyx how are you"))
        assertEquals("your g p s", say("your GPS"))
        assertEquals("Martin's budget", say("Martin's budget"))
        assertEquals("e mail", say("e-mail")) // each part is a letter or word
        assertEquals("re check", say("re-check"))
        assertEquals("It's", say("It’s")) // curly apostrophe
    }

    @Test
    fun numberWords() {
        assertEquals("zero", KokoroText.words(0))
        assertEquals("one hundred", KokoroText.words(100))
        assertEquals("two million five", KokoroText.words(2_000_005))
        assertEquals("ninety ninth", KokoroText.ordinal(99))
    }

    private companion object {
        /** Every word the number writer can produce is in the real dictionary. */
        val NUMBER_WORDS = setOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
            "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand", "million", "billion",
            "trillion", "minus", "oh", "first", "second", "third", "fourth", "fifth", "twelfth", "twentieth",
            "twenty", "dollar", "cent", "monday", "november",
        )
    }
}
