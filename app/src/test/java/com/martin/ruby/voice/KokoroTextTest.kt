package com.martin.ruby.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Kokoro is given as words: numbers, units, symbols and markdown written out or removed. */
class KokoroTextTest {

    private fun say(text: String) = KokoroText.normalize(text)

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
        assertEquals("one thousand two hundred thirty four steps", say("1,234 steps"))
        assertEquals("over two thousand kilometers per hour", say("over 2,000 kilometers per hour"))
        assertEquals("about four point five billion kilometers", say("about 4.5 billion kilometers"))
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
        assertEquals("eighteen forty six", say("1846"))
        assertEquals("nineteen oh five", say("1905"))
        assertEquals("Monday, November twenty four, twenty twenty six", say("Mon, Nov 24, 2026"))
    }

    @Test
    fun units() {
        assertEquals("about one thousand two hundred miles per hour", say("about 1,200 mph"))
        assertEquals("five kilometers", say("5 km"))
        assertEquals("one kilometer", say("1 km"))
        assertEquals("one point five kilometers", say("1.5km"))
        assertEquals("ninety kilometers per hour", say("90 km/h"))
        assertEquals("seventy two degrees Fahrenheit", say("72°F"))
        assertEquals("minus three degrees Celsius", say("-3 °C"))
        assertEquals("thirty six P S I", say("36 psi"))
        assertEquals("two hundred meters, five kilograms, three pounds", say("200 m, 5 kg, 3 lbs"))
        assertEquals("sixteen hours", say("16 h"))
        // Not after a number, or the wrong case: left alone.
        assertEquals("km stands for kilometer", say("km stands for kilometer"))
        assertEquals("five M people", say("5 M people"))
        // "in" stays a word: "5 in the morning" is likelier than inches.
        assertEquals("five in the morning", say("5 in the morning"))
    }

    @Test
    fun symbolsAndRanges() {
        assertEquals("Tom and Ann", say("Tom & Ann"))
        assertEquals("two plus two equals four", say("2 + 2 = 4"))
        assertEquals("number five", say("#5"))
        assertEquals("about ten minutes", say("~10 minutes"))
        assertEquals("three times four", say("3 × 4"))
        assertEquals("and or", say("and/or"))
        assertEquals("five to seven days", say("5-7 days"))
        assertEquals("five to seven days", say("5–7 days"))
        // A date isn't a range.
        assertEquals("twenty twenty six-eleven-twenty four", say("2026-11-24"))
    }

    @Test
    fun markdownAndQuotesAreNotSaid() {
        assertEquals("made of ices like water", say("made of \"ices\" like water"))
        assertEquals("made of ices like water", say("made of “ices” like water"))
        assertEquals("Neptune is far", say("**Neptune** is far"))
        assertEquals("very far", say("*very* far"))
        assertEquals("Wild weather", say("## Wild weather"))
        assertEquals("Moons and rings", say("- Moons and rings"))
        assertEquals("see the guide", say("see the [guide](https://example.com)"))
        assertEquals("run the build", say("run the `build`"))
        // Apostrophes inside words stay.
        assertEquals("it's Neptune's", say("it’s Neptune’s"))
    }

    @Test
    fun abbreviations() {
        assertEquals("gas giants, for example Jupiter", say("gas giants, e.g. Jupiter"))
        assertEquals("ice giants, that is Neptune", say("ice giants, i.e. Neptune"))
        assertEquals("milk, eggs, et cetera.", say("milk, eggs, etc."))
        assertEquals("Earth versus Neptune", say("Earth vs Neptune"))
    }

    @Test
    fun longSentences_splitAtPauses_shortOnesDont() {
        assertEquals(listOf("Good morning."), KokoroText.phrases("Good morning."))
        assertEquals(
            listOf("Your tyre check is at nine,", "and it's thirty six degrees outside, so take a coat."),
            KokoroText.phrases("Your tyre check is at nine, and it's thirty six degrees outside, so take a coat."),
        )
        // A short tail joins the phrase before it, and every word is kept, in order.
        val text = "First we'll check the oil and the tyres at the garage on Elm Street, then lunch, ok."
        val parts = KokoroText.phrases(text)
        assertEquals(text, parts.joinToString(" "))
        assertTrue(parts.toString(), parts.all { it.length >= KokoroText.MIN_PHRASE })
        // Dashes and semicolons are pauses too.
        assertEquals(2, KokoroText.phrases("The meeting moved to Thursday at three — bring the budget numbers please").size)
        assertEquals(2, KokoroText.phrases("Milk, eggs and bread from the store on the corner; then the pharmacy for Mom").size)
    }

    @Test
    fun aLongStretchWithNoPause_isCutBeforeAJoiningWord() {
        // The sentence after "Kuiper Belt" that left a two-second silence.
        val text = "It's one of the coldest places measured in the solar system and has geysers that shoot out nitrogen."
        assertEquals(
            listOf("It's one of the coldest places measured in the solar system", "and has geysers that shoot out nitrogen."),
            KokoroText.phrases(text),
        )
        // With no joining word, near the middle at a space; nothing is lost.
        val plain = "Abcdefgh ijklmnop qrstuvwx yzabcdef ghijklmn opqrstuv wxyzabcd efghijkl mnopqrst uvwxyzab cdefghij"
        val parts = KokoroText.phrases(plain)
        assertEquals(2, parts.size)
        assertEquals(plain, parts.joinToString(" "))
    }

    @Test
    fun theFirstPiecesAreShort_soSheStartsTalkingSoon() {
        val phrases = KokoroText.phrases(KokoroText.normalize(
            "Neptune is the eighth planet from the Sun and the farthest one out, about 4.5 billion kilometers (2.8 billion miles) away."
        ))
        val lead = KokoroText.leadIn(phrases)
        assertTrue(lead.toString(), lead[0].length <= KokoroText.LEAD_IN[0])
        assertTrue(lead.toString(), lead[1].length <= KokoroText.LEAD_IN[1])
        assertEquals(phrases.joinToString(" "), lead.joinToString(" ")) // nothing lost or reordered
        // Already short: left alone.
        assertEquals(listOf("Good morning."), KokoroText.leadIn(listOf("Good morning.")))
    }

    @Test
    fun numberWords() {
        assertEquals("zero", KokoroText.words(0))
        assertEquals("one hundred", KokoroText.words(100))
        assertEquals("two million five", KokoroText.words(2_000_005))
        assertEquals("ninety ninth", KokoroText.ordinal(99))
    }
}
