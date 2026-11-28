package com.martin.ruby.memory

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.martin.ruby.BuildConfig
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.ai.AiSettings
import com.martin.ruby.ai.FreeMode
import com.martin.ruby.backup.NightlyBackup
import com.martin.ruby.chat.ApiKeyStore
import com.martin.ruby.data.dataStore
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Reading and changing day summaries. */
class SummaryStore(context: Context) {

    private val appContext = context.applicationContext

    private suspend fun db() = withContext(Dispatchers.IO) { MemoryDatabase.get(appContext) }

    /** Newest day first; empty if memory can't be opened. */
    fun all(): Flow<List<DaySummary>> = flow {
        val dao = try {
            db().summaryDao()
        } catch (e: Exception) {
            emit(emptyList())
            return@flow
        }
        emitAll(dao.all())
    }.flowOn(Dispatchers.IO)

    /** Saves [text] as [day]'s summary, replacing any earlier one. */
    suspend fun save(day: String, text: String, messageCount: Int) {
        val db = db()
        db.withTransaction {
            db.summaryDao().deleteDay(day)
            db.summaryDao().insert(DaySummary(day = day, text = text, messageCount = messageCount, createdMillis = System.currentTimeMillis()))
        }
    }

    /** Deletes [day]'s summary; if the day is old enough, it's made again on the next run. */
    suspend fun delete(day: String) = db().summaryDao().deleteDay(day)
}

/** Whether summaries are made nightly, and how the last run went. */
class SummarySettings(private val context: Context) {

    data class Status(
        val nightlyOn: Boolean = true,
        val lastRunMillis: Long? = null,
        /** What the last run did, e.g. "Summarized 3 days." or why it stopped. */
        val lastResult: String? = null,
    )

    private object Keys {
        val ON = booleanPreferencesKey("summaries_nightly_on")
        val LAST_RUN = longPreferencesKey("summaries_last_run")
        val LAST_RESULT = stringPreferencesKey("summaries_last_result")
    }

    val status: Flow<Status> = context.dataStore.data.map { p ->
        Status(p[Keys.ON] ?: true, p[Keys.LAST_RUN], p[Keys.LAST_RESULT])
    }

    suspend fun setNightlyOn(on: Boolean) {
        context.dataStore.edit { it[Keys.ON] = on }
    }

    suspend fun recordRun(result: String) {
        context.dataStore.edit {
            it[Keys.LAST_RUN] = System.currentTimeMillis()
            it[Keys.LAST_RESULT] = result
        }
    }
}

/**
 * Phase 6 step 4: each night, summarize days of chat older than [AGE_DAYS] that don't have a
 * summary yet. At most [MAX_DAYS_PER_RUN] days a night, so a first run over a long history
 * is spread out; the rest follow on later nights.
 */
object Summaries {

    const val AGE_DAYS = 7L
    const val MAX_DAYS_PER_RUN = 14

    /** An age (in days) that makes [cutoffMillis] include today; for the debug run. */
    const val INCLUDE_TODAY = -1L

    private const val WORK_NAME = "nightly-summaries"
    private const val NOW_WORK_NAME = "summaries-now"

    /** Runs after the nightly backup's target hour. */
    private const val TARGET_HOUR = 3

    /** Summarizes one day; the app uses [ChatSummarizer], tests a fake. */
    fun interface Summarize {
        suspend fun summarize(day: String, messages: List<MemoryMessage>): String
    }

    data class RunResult(val made: Int, val failed: Int, val remaining: Boolean, val stoppedBecause: String?) {
        fun describe(): String = buildString {
            append(if (made == 1) "Summarized 1 day." else "Summarized $made days.")
            if (failed > 0) append(" $failed couldn't be summarized.")
            stoppedBecause?.let { append(" Stopped: $it") }
            if (remaining && stoppedBecause == null) append(" More to do on the next run.")
        }
    }

    /**
     * The first moment of the day [ageDays] before today: days before it get summarized.
     * [INCLUDE_TODAY] (-1) gives the start of tomorrow, so today counts too.
     */
    fun cutoffMillis(now: ZonedDateTime, ageDays: Long = AGE_DAYS): Long =
        now.toLocalDate().minusDays(ageDays).atStartOfDay(now.zone).toInstant().toEpochMilli()

    /** Summarizes the days that are due. Throws only if memory can't be opened. */
    suspend fun run(
        context: Context,
        summarize: Summarize,
        now: ZonedDateTime = ZonedDateTime.now(),
        ageDays: Long = AGE_DAYS,
    ): RunResult {
        val app = context.applicationContext
        val dao = withContext(Dispatchers.IO) { MemoryDatabase.get(app).summaryDao() }
        val store = SummaryStore(app)
        val days = dao.unsummarizedDays(cutoffMillis(now, ageDays), MAX_DAYS_PER_RUN + 1)
        var made = 0
        var failed = 0
        for (day in days.take(MAX_DAYS_PER_RUN)) {
            val messages = dao.messagesOn(day)
            if (messages.isEmpty()) continue
            try {
                store.save(day, summarize.summarize(day, messages), messages.size)
                made++
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatSummarizer.Failed) {
                SensingLog.add("Couldn't summarize $day: ${e.message}")
                if (e.stopRun) return RunResult(made, failed, true, e.message)
                failed++
            }
        }
        return RunResult(made, failed, days.size > MAX_DAYS_PER_RUN, null)
    }

    /**
     * One run with Free mode (Gemini Nano on the phone). Nano only works while Ruby is open, so
     * this runs when she's opened (and from "Summarize now"); the nightly job can't use it.
     */
    suspend fun runWithFreeMode(context: Context, ageDays: Long = AGE_DAYS): RunResult {
        val app = context.applicationContext
        return run(app, summarize = FreeSummarizer(), ageDays = ageDays).also {
            SummarySettings(app).recordRun("Free mode: ${it.describe()}")
            SensingLog.add("Summaries (Free mode): ${it.describe()}")
        }
    }

    /** Free mode: summarize any days that are due, while Ruby is open. At most every few hours. */
    fun catchUpInFreeMode(context: Context) {
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now - lastFreeCatchUp < FREE_CATCH_UP_GAP_MS && lastFreeCatchUp != 0L) return
        lastFreeCatchUp = now
        freeScope.launch {
            if (AiSettings(app).current() != AiEngine.FREE || !FreeMode.ready()) return@launch
            runCatching { runWithFreeMode(app) }.onFailure { SensingLog.add("Summaries (Free mode) failed: ${it.message}") }
        }
    }

    private var lastFreeCatchUp = 0L
    private const val FREE_CATCH_UP_GAP_MS = 6 * 60 * 60_000L
    private val freeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One run with Claude, as the nightly job and "Summarize now" do it. Records the result. */
    suspend fun runWithClaude(context: Context, ageDays: Long = AGE_DAYS): RunResult? {
        val app = context.applicationContext
        val settings = SummarySettings(app)
        val key = ApiKeyStore(app).load()
        if (key == null) {
            settings.recordRun("Skipped: no API key saved.")
            return null
        }
        val summarizer = ChatSummarizer(key)
        return try {
            run(app, summarize = { day, messages -> summarizer.summarize(day, messages) }, ageDays = ageDays).also {
                settings.recordRun(it.describe())
                SensingLog.add("Summaries: ${it.describe()}")
            }
        } finally {
            withContext(Dispatchers.IO) { summarizer.close() }
        }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SummaryWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi, not mobile data
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(NightlyBackup.delayUntil(TARGET_HOUR, ZonedDateTime.now()).toMinutes(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** "Summarize now": the same job once, needing only a network connection. */
    /**
     * [includeRecent] (debug builds only) summarizes every day with messages, today
     * included, so summaries can be tried without waiting a week. Today's summary is deleted
     * as soon as another message is saved today (see [MemoryStore.add]), so it can't go stale.
     */
    fun runNow(context: Context, includeRecent: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInputData(workDataOf(SummaryWorker.INCLUDE_RECENT to (includeRecent && BuildConfig.DEBUG)))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Makes sure the nightly job is scheduled if it's on, e.g. after a restore or reinstall. */
    suspend fun ensureScheduled(context: Context) {
        if (SummarySettings(context).status.first().nightlyOn) schedule(context)
    }

    /** The day key ("2026-11-23") of a message, in the phone's time zone. */
    fun dayOf(message: MemoryMessage, zone: ZoneId = ZoneId.systemDefault()) = SummaryText.dayOf(message.timeMillis, zone)
}

/** The nightly run (and "Summarize now"). */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val ageDays = if (inputData.getBoolean(INCLUDE_RECENT, false) && BuildConfig.DEBUG) Summaries.INCLUDE_TODAY else Summaries.AGE_DAYS
        if (AiSettings(applicationContext).current() == AiEngine.FREE) {
            // Free mode never hands summaries to Claude. Gemini Nano works only while Ruby is
            // open: this works for "Summarize now"; at night it's refused, and the days are
            // summarized the next time Ruby is opened.
            try {
                Summaries.runWithFreeMode(applicationContext, ageDays)
            } catch (e: Exception) {
                SummarySettings(applicationContext).recordRun("Free mode: waiting until Ruby is open (${e.message})")
            }
        } else if (AiSettings(applicationContext).current().usesModelsScreen) {
            // Like Free mode, these never hand Ruby's memories to Claude by themselves. Summaries
            // wait until Claude or Free mode is chosen again (not built for these models yet).
            SummarySettings(applicationContext).recordRun("Waiting: summaries aren't made with on-phone or cloud/PC models yet")
        } else {
            Summaries.runWithClaude(applicationContext, ageDays)
        }
        Result.success()
    } catch (e: CancellationException) {
        throw e // stopped (e.g. unplugged); runs again later
    } catch (e: Exception) {
        SummarySettings(applicationContext).recordRun("Failed: ${e.message ?: e.javaClass.simpleName}")
        Result.success()
    }

    companion object {
        /** Input flag for a debug run that includes every day, today too. */
        const val INCLUDE_RECENT = "include_recent"
    }
}
