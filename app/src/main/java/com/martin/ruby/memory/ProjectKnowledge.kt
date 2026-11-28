package com.martin.ruby.memory

import android.content.Context
import androidx.room.withTransaction
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ruby's project knowledge: Martin's overview of her whole project, to-do list and ideas
 * (docs/Ruby_Project_Overview.md, bundled with the app). It lives in her memory as notes, one
 * per section, so it's found by memory search and chat recall, goes into backups and restores
 * with everything else, and is never summarized away or moved to the Drive archive.
 *
 * Each time Ruby starts, the notes are compared with the bundled file: if the file changed
 * (a new build with a new overview), the old notes are replaced, never added to.
 */
object ProjectKnowledge {

    const val ASSET = "knowledge/Ruby_Project_Overview.md"

    /** The longest a note may be; longer sections are split at their sub-headings or list items. */
    const val MAX_NOTE_CHARS = 2_500

    /** The overview as notes: one per "## " section, each starting with what it is and its heading. */
    fun notes(markdown: String): List<String> {
        val lines = markdown.replace("\r\n", "\n").lines()
        val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: "Project overview"
        val sections = mutableListOf<Pair<String, MutableList<String>>>()
        var current: Pair<String, MutableList<String>> = "Introduction" to mutableListOf()
        for (line in lines) {
            when {
                line.startsWith("# ") -> Unit
                line.startsWith("## ") -> {
                    sections += current
                    current = line.removePrefix("## ").trim() to mutableListOf()
                }
                line.trim() == "---" -> Unit
                else -> current.second += line
            }
        }
        sections += current
        return sections.flatMap { (heading, body) ->
            val text = body.joinToString("\n").trim()
            if (text.isEmpty()) emptyList() else {
                val parts = split(text)
                parts.mapIndexed { i, part ->
                    val label = if (parts.size > 1) "$heading (part ${i + 1} of ${parts.size})" else heading
                    "Project knowledge, from \"$title\", section \"$label\":\n$part"
                }
            }
        }
    }

    /** Splits [text] into pieces of at most [MAX_NOTE_CHARS], at "### " headings, then at lines. */
    private fun split(text: String): List<String> {
        if (text.length <= MAX_NOTE_CHARS) return listOf(text)
        // Blocks: a sub-heading, list item or paragraph with the lines under it (indented sub-items stay with their item).
        val blocks = mutableListOf<StringBuilder>()
        for (line in text.lines()) {
            val startsBlock = line.startsWith("### ") || line.startsWith("- ") || Regex("^\\d+\\. ").containsMatchIn(line) ||
                (line.isNotBlank() && !line.startsWith(" ") && blocks.lastOrNull()?.endsWith("\n\n") == true)
            if (startsBlock || blocks.isEmpty()) blocks += StringBuilder()
            blocks.last().append(line).append('\n')
        }
        // Packed whole into pieces; a block longer than the limit stays whole (better long than cut mid-sentence).
        val pieces = mutableListOf<String>()
        val piece = StringBuilder()
        for (block in blocks) {
            if (piece.isNotBlank() && piece.length + block.length > MAX_NOTE_CHARS) {
                pieces += piece.toString().trim()
                piece.clear()
            }
            piece.append(block)
        }
        if (piece.isNotBlank()) pieces += piece.toString().trim()
        return pieces
    }

    /** Whether the notes in memory are out of date (or missing) compared with [fresh]. */
    fun needsUpdate(stored: List<String>, fresh: List<String>): Boolean = stored != fresh

    /**
     * Brings the notes in memory up to date with the bundled overview. Safe to call on every
     * start: does nothing if they already match. Returns how many notes were written (0 if none).
     */
    suspend fun sync(context: Context): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val markdown = try {
            app.assets.open(ASSET).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            SensingLog.add("Project knowledge: the overview isn't in this build.")
            return@withContext 0
        }
        syncFrom(app, markdown)
    }

    /** As [sync], from [markdown] (tests pass their own). */
    internal suspend fun syncFrom(context: Context, markdown: String): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val fresh = notes(markdown)
        val db = MemoryDatabase.get(app)
        val stored = db.dao().knowledge().map { it.text }
        if (!needsUpdate(stored, fresh)) return@withContext 0
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.dao().deleteKnowledge()
            db.dao().insertAll(
                fresh.mapIndexed { i, text ->
                    // A millisecond apart, so they keep their order.
                    MemoryMessage(timeMillis = now + i, role = MemoryMessage.ROLE_KNOWLEDGE, text = text, context = CONTEXT)
                }
            )
        }
        SensingLog.add(
            if (stored.isEmpty()) "Project knowledge: added ${fresh.size} notes from the overview."
            else "Project knowledge: the overview changed, so its ${stored.size} notes were replaced with ${fresh.size}."
        )
        fresh.size
    }

    const val CONTEXT = "Project knowledge"
}
