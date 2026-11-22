package com.martin.ruby.memory

import android.content.Context
import android.os.SystemClock
import com.martin.ruby.decision.ContextStatus
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
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
     * current state.
     */
    suspend fun add(role: String, text: String) {
        val context = (ContextStatus.strongestRecent(SystemClock.elapsedRealtime())
            ?: ContextStatus.reading.value?.state)?.label
        dao().insert(
            MemoryMessage(timeMillis = System.currentTimeMillis(), role = role, text = text, context = context)
        )
    }

    suspend fun delete(message: MemoryMessage) = dao().delete(message.id)

    suspend fun deleteAll() = dao().deleteAll()
}
