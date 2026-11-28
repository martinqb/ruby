package com.martin.ruby.memory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ruby's project knowledge in her real (encrypted) memory: added once, replaced (not doubled)
 * when the overview changes, found by recall, and left out of summaries and the archive.
 */
@RunWith(AndroidJUnit4::class)
class ProjectKnowledgeDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db get() = MemoryDatabase.get(context)

    private val bundled: String get() = context.assets.open(ProjectKnowledge.ASSET).bufferedReader().use { it.readText() }

    @After
    fun backToTheBundledOverview() = runBlocking {
        ProjectKnowledge.sync(context)
        Unit
    }

    @Test
    fun theOverview_isBundled_andSyncingTwiceMakesNoDuplicates() = runBlocking {
        assertTrue(bundled.contains("Creator and architect"))
        db.dao().deleteKnowledge()
        val written = ProjectKnowledge.sync(context)
        assertTrue(written >= 6)
        assertEquals(0, ProjectKnowledge.sync(context)) // already up to date
        assertEquals(ProjectKnowledge.notes(bundled), db.dao().knowledge().map { it.text })
    }

    @Test
    fun aNewOverview_replacesTheNotes() = runBlocking {
        ProjectKnowledge.sync(context)
        val before = db.dao().knowledge().size
        val newer = bundled + "\n## 7. A brand-new section\n\n- Something Martin added later: zeppelin parking.\n"
        assertEquals(before + 1, ProjectKnowledge.syncFrom(context, newer))
        val notes = db.dao().knowledge()
        assertEquals(before + 1, notes.size)
        assertEquals(1, notes.count { it.text.contains("zeppelin parking") })
        // Back to the bundled one: the extra note goes away again.
        ProjectKnowledge.sync(context)
        assertEquals(before, db.dao().knowledge().size)
    }

    @Test
    fun recall_findsWhoCreatedHer_andHerToDoList() = runBlocking {
        ProjectKnowledge.sync(context)
        val store = MemoryStore(context)
        fun texts(r: List<MemoryRecall.Recalled>) = r.filterIsInstance<MemoryRecall.Recalled.Message>().map { it.message }

        val creator = texts(store.recall("Who created you?", emptySet()))
        assertTrue("found: ${creator.map { it.text.take(80) }}", creator.any { it.isKnowledge && it.text.contains("Creator and architect:** Martin") })

        val todo = texts(store.recall("What's on your to-do list?", emptySet()))
        assertTrue("found: ${todo.map { it.text.take(80) }}", todo.any { it.isKnowledge && it.text.contains("section \"5. To-do list") })

        // And it shows in the memory screen's search.
        val searched = store.search("architect").first()
        assertTrue(searched.any { it.isKnowledge })
    }

    @Test
    fun knowledge_isNeverSummarized_orPackedAway() = runBlocking {
        ProjectKnowledge.sync(context)
        val ids = db.dao().knowledge().map { it.id }.toSet()
        // Even if it were old enough, its day isn't offered to the summarizer, and its notes aren't in a day's messages.
        val far = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
        val days = db.summaryDao().unsummarizedDays(far, 10_000)
        days.forEach { day -> assertTrue(db.summaryDao().messagesOn(day).none { it.id in ids }) }
        // And the storage limit never moves it to the archive.
        assertTrue(db.dao().packable(keepNewest = 0, now = far, limit = 100_000).none { it.id in ids })
    }
}
