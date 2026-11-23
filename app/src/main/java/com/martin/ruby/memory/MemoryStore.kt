package com.martin.ruby.memory

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.martin.ruby.data.CarSettings
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.decision.ContextStatus
import com.martin.ruby.drive.DriveArchiveCloud
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * What the rest of the app uses to read and write memory. Opening the database (which
 * reads the Keystore) always happens on a background thread.
 */
class MemoryStore(context: Context) {

    private val appContext = context.applicationContext

    private suspend fun dao(): MemoryDao =
        withContext(Dispatchers.IO) { MemoryDatabase.get(appContext).dao() }

    /** Tries to open the database. Returns null if it worked, or what went wrong. */
    suspend fun openError(): String? = try {
        dao()
        null
    } catch (e: Exception) {
        SensingLog.add("Memory couldn't open: ${e.message}")
        e.message ?: e.javaClass.simpleName
    }

    /** Wraps a flow so the database opens in the background; if it can't, [onError] is shown. */
    private fun <T> fromDao(onError: T, query: (MemoryDao) -> Flow<T>): Flow<T> = flow {
        val dao = try {
            dao()
        } catch (e: Exception) {
            emit(onError) // openError() explains why
            return@flow
        }
        emitAll(query(dao))
    }.flowOn(Dispatchers.IO)

    /** The newest messages first. */
    fun recent(limit: Int = 200): Flow<List<MemoryMessage>> = fromDao(emptyList()) { it.recent(limit) }

    fun count(): Flow<Int> = fromDao(0) { it.count() }

    /** Messages matching [typed], best matches first. Empty if there's nothing to search for. */
    fun search(typed: String): Flow<List<MemoryMessage>> {
        val fts = MemorySearch.ftsQuery(typed) ?: return flowOf(emptyList())
        return fromDao(emptyList()) { it.search(MemoryDatabase.searchQuery(fts)) }
    }

    /**
     * Saves a message, tagged with the strongest state of the last 10 seconds (so taking
     * the phone out to type doesn't turn "Walking" into "Moving"), or failing that the
     * current state. Returns its id.
     */
    suspend fun add(role: String, text: String): Long {
        val context = (ContextStatus.strongestRecent(SystemClock.elapsedRealtime())
            ?: ContextStatus.reading.value?.state)?.label
        val id = dao().insert(
            MemoryMessage(timeMillis = System.currentTimeMillis(), role = role, text = text, context = context)
        )
        MemoryLimit.check(appContext)
        return id
    }

    /**
     * The few past messages most related to [message], for sending along to Claude, leaving
     * out [exclude] (the current conversation, which is sent in full anyway). Empty if
     * nothing matches. Archived messages aren't searched.
     */
    suspend fun recall(message: String, exclude: Set<Long>): List<MemoryMessage> {
        val fts = MemoryRecall.ftsQuery(message) ?: return emptyList()
        return dao().searchNow(MemoryDatabase.recallQuery(fts, MemoryRecall.MAX_MEMORIES + exclude.size))
            .filter { it.id !in exclude }
            .take(MemoryRecall.MAX_MEMORIES)
    }

    suspend fun delete(message: MemoryMessage) = dao().delete(message.id)

    /**
     * Deletes every message on the phone, and the compressed bundles kept on the phone.
     * Memories moved to Google Drive stay there.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        val db = MemoryDatabase.get(appContext)
        db.withTransaction {
            db.dao().deleteAll()
            db.archiveDao().deleteAllOnPhone()
        }
        MemoryLimit.check(appContext)
    }

    /** Bundles of older messages packed away by the storage limit, oldest first. */
    fun archives(): Flow<List<ArchiveSummary>> = flow {
        val dao = try {
            withContext(Dispatchers.IO) { MemoryDatabase.get(appContext).archiveDao() }
        } catch (e: Exception) {
            emit(emptyList())
            return@flow
        }
        emitAll(dao.stored())
    }.flowOn(Dispatchers.IO)

    /**
     * Puts an archived bundle back into memory. Throws [MemoryArchiver.NeedsOldSecret] if it
     * needs an earlier passphrase; pass it as [secret] then.
     */
    suspend fun bringBack(archiveId: Long, secret: String? = null): Int {
        val count = MemoryLimit.archiver(appContext).bringBack(
            archiveId, cloud = { DriveArchiveCloud.connect(appContext) }, secret = secret,
        )
        SensingLog.add("Brought back $count archived messages into memory.")
        MemoryLimit.check(appContext)
        return count
    }

    /**
     * Writes everything in memory to [writer] (see [MemoryExporter]); the JSON format also
     * gets the saved places and the car. Memory itself doesn't change.
     */
    suspend fun export(
        writer: java.io.Writer,
        format: MemoryExport.Format,
        includeDrive: Boolean,
        onProgress: (Int) -> Unit = {},
    ): MemoryExport.Summary {
        val json = format == MemoryExport.Format.JSON
        val car = if (json) CarSettings(appContext).car.first()?.let { MemoryExport.Car(it.name, it.address) } else null
        val places = if (json) PlaceStore(appContext).places.first().map { MemoryExport.Place(it.name, it.latitude, it.longitude) }
        else emptyList()
        val db = withContext(Dispatchers.IO) { MemoryDatabase.get(appContext) }
        val summary = MemoryExporter(db).export(
            writer,
            MemoryExporter.Options(format, includeDrive, car, places),
            cloud = { DriveArchiveCloud.connect(appContext) },
            onProgress = onProgress,
        )
        SensingLog.add(
            "Exported ${summary.total} messages as ${format.extension}" +
                if (summary.skipped.isEmpty()) "." else "; ${summary.skipped.size} parts not included."
        )
        return summary
    }

    /** For testing the storage limit: saves [count] made-up notes of about 600 characters. */
    suspend fun addTestNotes(count: Int) {
        dao().insertAll(TestNotes.make(count, System.currentTimeMillis()))
        SensingLog.add("Added $count test notes to memory.")
        MemoryLimit.check(appContext)
    }

    suspend fun deleteTestNotes(): Int = dao().deleteTestNotes().also { MemoryLimit.check(appContext) }

}
