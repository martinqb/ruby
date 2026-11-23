package com.martin.ruby.memory

import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.memory.MemoryRecall.Recalled
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject

/**
 * Day summaries on a real (emulated) phone: which days get summarized, that SQLite's idea of
 * a day matches the app's, search, recall, and what deleting does. Uses a fake summarizer,
 * so nothing is sent to Claude. Wipes memory, so it runs on an emulator only.
 */
@RunWith(AndroidJUnit4::class)
class SummariesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.systemDefault()
    private val now = ZonedDateTime.now(zone)

    private fun isEmulator() = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")

    /** Noon, [daysAgo] days ago. */
    private fun daysAgo(daysAgo: Long, hour: Int = 12) =
        now.toLocalDate().minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun put(text: String, time: Long, role: String = MemoryMessage.ROLE_USER): Long =
        MemoryDatabase.get(context).dao().insert(MemoryMessage(timeMillis = time, role = role, text = text))

    /** Writes "Summary: " plus the day's messages, so tests can see what went in. */
    private val fake = Summaries.Summarize { day, messages -> "Summary of $day: " + messages.joinToString(" / ") { it.text } }

    @Test
    fun oldDaysAreSummarized_recentOnesAreNot() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        MemoryStore(context).deleteAll()
        put("Booked the tyre check for Friday", daysAgo(10, 9))
        put("Tyre shop is on Elm Street", daysAgo(10, 23)) // late evening: still the same day
        put("Bought winter wipers", daysAgo(9))
        put("Recent chat about lunch", daysAgo(3))
        put("Today's chat", daysAgo(0))

        val result = Summaries.run(context, fake, now)
        assertEquals(2, result.made)

        val summaries = SummaryStore(context).all().first().sortedBy { it.day }
        val tenDaysAgo = SummaryText.dayOf(daysAgo(10), zone)
        assertEquals(listOf(tenDaysAgo, SummaryText.dayOf(daysAgo(9), zone)), summaries.map { it.day })
        // SQLite grouped both of that day's messages, in order, like the app's day key does.
        assertEquals("Summary of $tenDaysAgo: Booked the tyre check for Friday / Tyre shop is on Elm Street", summaries[0].text)
        assertEquals(2, summaries[0].messageCount)

        // A second run has nothing left to do.
        assertEquals(0, Summaries.run(context, fake, now).made)
        MemoryStore(context).deleteAll()
    }

    @Test
    fun recall_findsOldDaysThroughTheirSummaries() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        val store = MemoryStore(context)
        store.deleteAll()
        repeat(4) { put("Tyre note $it", daysAgo(12, 8 + it)) }
        Summaries.run(context, fake, now)
        val day = SummaryText.dayOf(daysAgo(12), zone)

        val recalled = store.recall("What do I know about the tyres?", exclude = emptySet())
        // The two best matches word for word, then the day's summary for the rest.
        assertEquals(2, recalled.count { it is Recalled.Message })
        assertEquals(listOf(day), recalled.filterIsInstance<Recalled.Summary>().map { it.summary.day })

        // Once the messages are gone from the phone (as when the storage limit packs them
        // away), the summary alone still finds the day.
        MemoryDatabase.get(context).dao().deleteAll()
        val onlySummary = store.recall("tyre", exclude = emptySet())
        assertEquals(listOf(day), onlySummary.filterIsInstance<Recalled.Summary>().map { it.summary.day })
        store.deleteAll()
    }

    @Test
    fun deletingAMessage_deletesItsDaysSummary_andTheNextRunRemakesIt() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        val store = MemoryStore(context)
        store.deleteAll()
        put("Keep this", daysAgo(8, 9))
        val secret = put("Forget this", daysAgo(8, 10))
        Summaries.run(context, fake, now)
        assertTrue(SummaryStore(context).all().first().single().text.contains("Forget this"))

        store.delete(MemoryDatabase.get(context).dao().pageAfter(0, 0, 10).first { it.id == secret })
        assertEquals(emptyList<DaySummary>(), SummaryStore(context).all().first())
        // The search index forgot it too.
        assertEquals(emptyList<Recalled>(), store.recall("forget", exclude = emptySet()).filterIsInstance<Recalled.Summary>())

        Summaries.run(context, fake, now)
        val remade = SummaryStore(context).all().first().single()
        assertEquals(1, remade.messageCount)
        assertTrue(!remade.text.contains("Forget this"))

        store.deleteAll()
        assertEquals(emptyList<DaySummary>(), SummaryStore(context).all().first())
    }

    /** The debug run summarizes today; the next message saved today must remove that summary. */
    @Test
    fun includingToday_andANewMessageRemovesTodaysSummary() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        val store = MemoryStore(context)
        store.deleteAll()
        store.add(MemoryMessage.ROLE_USER, "Morning note about the dentist")
        put("An old day", daysAgo(9))
        val today = SummaryText.dayOf(System.currentTimeMillis(), zone)

        // The normal run leaves today alone; the debug run includes it.
        assertEquals(1, Summaries.run(context, fake, now).made)
        assertEquals(1, Summaries.run(context, fake, now, ageDays = Summaries.INCLUDE_TODAY).made)
        assertTrue(SummaryStore(context).all().first().any { it.day == today })

        store.add(MemoryMessage.ROLE_USER, "Afternoon note")
        val days = SummaryStore(context).all().first().map { it.day }
        assertTrue("Today's summary went with the new message", today !in days)
        assertEquals("The old day's summary is untouched", listOf(SummaryText.dayOf(daysAgo(9), zone)), days)

        // Made again, it covers the whole day.
        Summaries.run(context, fake, now, ageDays = Summaries.INCLUDE_TODAY)
        assertTrue(SummaryStore(context).all().first().single { it.day == today }.text.contains("Afternoon note"))
        store.deleteAll()
    }

    @Test
    fun aFailedDay_isSkipped_andABadKeyStopsTheRun() = runBlocking {
        assumeTrue("Wipes memory: runs on an emulator only", isEmulator())
        MemoryStore(context).deleteAll()
        put("Day A", daysAgo(11))
        put("Day B", daysAgo(10))
        put("Day C", daysAgo(9))
        val dayA = SummaryText.dayOf(daysAgo(11), zone)
        val declines = Summaries.Summarize { day, messages ->
            if (day == dayA) throw ChatSummarizer.Failed("declined", stopRun = false) else fake.summarize(day, messages)
        }
        val result = Summaries.run(context, declines, now)
        assertEquals(2, result.made)
        assertEquals(1, result.failed)

        MemoryStore(context).deleteAll()
        put("Day D", daysAgo(10))
        val badKey = Summaries.Summarize { _, _ -> throw ChatSummarizer.Failed("Anthropic didn't accept the API key.", stopRun = true) }
        val stopped = Summaries.run(context, badKey, now)
        assertEquals("Anthropic didn't accept the API key.", stopped.stoppedBecause)
        MemoryStore(context).deleteAll()
    }

    /**
     * The phone's memory is at version 3; upgrading to 4 must keep everything. Builds a real
     * version 3 file from the saved schema, encrypted with SQLCipher and Ruby's key like the
     * phone's, then opens it the way the app does, which runs the upgrade.
     */
    @Test
    fun upgradeFrom3To4_keepsMessagesAndReminders() = runBlocking {
        val name = "upgrade-test.db"
        context.deleteDatabase(name)
        val schema = JSONObject(
            // The saved schemas are packed into the test app, not the app.
            InstrumentationRegistry.getInstrumentation().context.assets.open("com.martin.ruby.memory.MemoryDatabase/3.json").bufferedReader().use { it.readText() }
        ).getJSONObject("database")
        val statements = buildList {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                add(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) add(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) add(setup.getString(i))
        }

        System.loadLibrary("sqlcipher")
        val hex = MemoryKey.get(context).joinToString("") { "%02x".format(it) }
        val helper = SupportOpenHelperFactory("x'$hex'".toByteArray()).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO messages (timeMillis, role, text, context) VALUES (1000, 'user', 'Parked on level 3', 'Driving')")
            execSQL("INSERT INTO reminders (text, dueMillis, createdMillis, state) VALUES ('Check the oven', 5000, 900, 'pending')")
        }
        helper.close()

        // Opening it as the app does runs Room's upgrade to version 4.
        val db = MemoryDatabase.open(context, name)
        try {
            assertEquals(4, db.openHelper.writableDatabase.version)
            assertEquals(listOf("Parked on level 3"), db.dao().pageAfter(0, 0, 10).map { it.text })
            assertEquals(listOf("Check the oven"), db.reminderDao().pendingNow().map { it.text })
            assertEquals(emptyList<DaySummary>(), db.summaryDao().all().first())
            // The new table and its search index work.
            db.summaryDao().insert(DaySummary(day = "2026-11-01", text = "The user parked on level 3.", messageCount = 1, createdMillis = 0))
            val found = db.summaryDao().searchNow(MemoryDatabase.summarySearchQuery("\"level\"*", 5))
            assertEquals(listOf("2026-11-01"), found.map { it.day })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
