package com.martin.ruby.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Martin's project overview as notes in Ruby's memory. */
class ProjectKnowledgeTest {

    /** The real overview, as bundled with the app. */
    private val overview = File("../docs/Ruby_Project_Overview.md").readText()

    @Test
    fun theOverview_becomesOneNotePerSection_eachSmallEnoughToRecall() {
        val notes = ProjectKnowledge.notes(overview)
        assertTrue("several sections", notes.size >= 6)
        notes.forEach { assertTrue(it.take(80), it.length <= ProjectKnowledge.MAX_NOTE_CHARS + 200) }
        // Each says what it is and which section, so a note found alone still makes sense.
        notes.forEach { assertTrue(it.startsWith("Project knowledge, from \"Ruby — Project Overview, To-Do and Ideas\", section \"")) }
        assertTrue(notes.any { it.contains("section \"1. Who you are\"") && it.contains("Creator and architect:** Martin") })
        assertTrue(notes.any { it.contains("section \"5. To-do list") && it.contains("Web search and links in chat") })
        assertTrue(notes.any { it.contains("section \"6. Ideas for later") })
        // Nothing lost: every line of the overview is in some note.
        val all = notes.joinToString("\n")
        overview.lines().map { it.trim() }.filter { it.isNotEmpty() && it != "---" && !it.startsWith("#") }
            .forEach { assertTrue(it.take(60), all.contains(it)) }
    }

    @Test
    fun aLongSection_isSplitInParts_atListItems() {
        val long = "# T\n\n## Big\n" + (1..80).joinToString("\n") { "- item $it with some words to make it long enough" }
        val notes = ProjectKnowledge.notes(long)
        assertTrue(notes.size > 1)
        assertTrue(notes[0].contains("section \"Big (part 1 of ${notes.size})\""))
        notes.forEach { assertTrue(it.length <= ProjectKnowledge.MAX_NOTE_CHARS + 100) }
        assertTrue(notes.joinToString("\n").contains("- item 80 with"))
    }

    @Test
    fun theSameFile_needsNoUpdate_aChangedOneDoes() {
        val notes = ProjectKnowledge.notes(overview)
        assertEquals(notes, ProjectKnowledge.notes(overview))
        assertFalse(ProjectKnowledge.needsUpdate(notes, notes))
        assertTrue(ProjectKnowledge.needsUpdate(emptyList(), notes))
        assertTrue(ProjectKnowledge.needsUpdate(notes, ProjectKnowledge.notes(overview + "\n- one more idea\n")))
    }

    @Test
    fun hyphenatedWords_areSearchedAsPhrases() {
        assertEquals("\"to do\" OR \"list\"*", MemoryRecall.ftsQuery("What's on your to-do list?"))
        assertEquals("\"wi fi\" OR \"backup\"* OR \"run\"*", MemoryRecall.ftsQuery("Did the Wi-Fi backup run?"))
    }

    private fun knowledge(id: Long, text: String) =
        MemoryMessage(id = id, timeMillis = 1_000 + id, role = MemoryMessage.ROLE_KNOWLEDGE, text = text, context = ProjectKnowledge.CONTEXT)

    private fun chat(id: Long, text: String) = MemoryMessage(id = id, timeMillis = 1_000 + id, role = MemoryMessage.ROLE_USER, text = text)

    @Test
    fun inRecall_knowledgeIsNeverSwappedForASummary_andAtMostTwoGo() {
        val daySummary = DaySummary(day = "1970-01-01", text = "A summary", messageCount = 3, createdMillis = 0)
        val ranked = listOf(knowledge(1, "a"), knowledge(2, "b"), knowledge(3, "c"), chat(4, "d"), chat(5, "e"), chat(6, "f"))
        val chosen = MemoryRecall.choose(ranked, { daySummary }, emptyList())
        val kept = chosen.filterIsInstance<MemoryRecall.Recalled.Message>().map { it.message }
        assertEquals(listOf(1L, 2L), kept.filter { it.isKnowledge }.map { it.id })
        // The chat messages still get their usual treatment (best two word for word, then the day's summary).
        assertTrue(chosen.any { it is MemoryRecall.Recalled.Summary })
    }

    @Test
    fun inRecall_knowledgeIsLabelledAsSuch_andKeptLong() {
        val long = "Project knowledge, from \"X\", section \"5. To-do list\":\n" + "- a to-do item. ".repeat(100)
        val text = MemoryRecall.format(listOf(knowledge(1, long)))!!
        assertTrue(text.contains("- From your project knowledge (Martin's overview of your project, kept up to date): Project knowledge"))
        assertFalse("not labelled as something someone said", text.contains("said"))
        assertTrue("longer than an ordinary memory", text.length > MemoryRecall.MAX_CHARS + 200)
    }
}
