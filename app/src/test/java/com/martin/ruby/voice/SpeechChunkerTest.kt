package com.martin.ruby.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechChunkerTest {

    @Test
    fun sentencesComeOutAsSoonAsTheyreComplete() {
        val c = SpeechChunker()
        assertEquals(emptyList<String>(), c.feed("It's 40 degrees"))
        assertEquals(emptyList<String>(), c.feed("It's 40 degrees out."))  // no space yet: maybe "out.com"
        assertEquals(listOf("It's 40 degrees out."), c.feed("It's 40 degrees out. Take a"))
        assertEquals(listOf("Take a coat!"), c.feed("It's 40 degrees out. Take a coat! Drive"))
        assertEquals(emptyList<String>(), c.feed("It's 40 degrees out. Take a coat! Drive safe."))
        assertEquals(listOf("Drive safe."), c.finish())
    }

    @Test
    fun severalSentencesAtOnce_areSplit() {
        val c = SpeechChunker()
        assertEquals(listOf("One.", "Two?", "\"Three!\""), c.feed("One. Two? \"Three!\" Four"))
        assertEquals(listOf("Four"), c.finish())
    }

    @Test
    fun decimalsAndLineBreaks() {
        val c = SpeechChunker()
        assertEquals(listOf("Pressure is 35.5 psi", "Fuel is fine"), c.feed("Pressure is 35.5 psi\nFuel is fine\n"))
        assertEquals(emptyList<String>(), c.finish())
    }

    @Test
    fun longRunWithoutAnEnd_breaksAtASpace() {
        val c = SpeechChunker(maxChars = 20)
        val parts = c.feed("one two three four five six seven eight")
        assertEquals(listOf("one two three four"), parts)
        assertEquals(listOf("five six seven eight"), c.finish())
    }

    @Test
    fun punctuationOnlyPieces_areSkipped() {
        val c = SpeechChunker()
        assertEquals(listOf("Sure."), c.feed("Sure. ... "))
    }
}
