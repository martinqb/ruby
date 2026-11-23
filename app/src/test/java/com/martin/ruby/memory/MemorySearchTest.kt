package com.martin.ruby.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemorySearchTest {

    @Test
    fun words_areQuotedAndMatchTheStartOfWords() {
        assertEquals("\"tyre\"* \"pressure\"*", MemorySearch.ftsQuery("tyre pressure"))
    }

    @Test
    fun ftsSyntax_isTreatedAsText() {
        // Quotes, stars, minus and OR would otherwise change or break the query.
        assertEquals("\"OR\"* \"park\"*", MemorySearch.ftsQuery("\" OR park* -"))
    }

    @Test
    fun accentsAndNumbers_areKept() {
        assertEquals("\"café\"* \"36\"* \"psi\"*", MemorySearch.ftsQuery("café, 36 psi?"))
    }

    @Test
    fun nothingToSearch_isNull() {
        assertNull(MemorySearch.ftsQuery("  ?!  "))
        assertNull(MemorySearch.ftsQuery(""))
    }
}
