package com.martin.ruby.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * A short summary of one day of chat (Phase 6 step 4), made by Claude from that day's
 * messages. The messages themselves stay; the summary is extra, so recall can send a few
 * lines instead of many messages, and days the storage limit packed away stay findable.
 */
@Entity(tableName = "summaries", indices = [Index(value = ["day"], unique = true)])
data class DaySummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The day, in the phone's time zone when it was summarized, as "2026-11-23". */
    val day: String,
    val text: String,
    /** How many messages it summarizes. */
    val messageCount: Int,
    val createdMillis: Long,
)

@Dao
interface SummaryDao {

    /**
     * Adds a summary. To replace one, delete the day first in the same transaction (see
     * [SummaryStore.save]): SQLite's REPLACE skips delete triggers, which would leave the
     * search index out of step.
     */
    @Insert
    suspend fun insert(summary: DaySummary): Long

    @Query("SELECT * FROM summaries WHERE day = :day")
    suspend fun forDay(day: String): DaySummary?

    @Query("SELECT * FROM summaries WHERE day IN (:days)")
    suspend fun forDays(days: List<String>): List<DaySummary>

    /** Newest day first. */
    @Query("SELECT * FROM summaries ORDER BY day DESC")
    fun all(): Flow<List<DaySummary>>

    @Query("SELECT COUNT(*) FROM summaries")
    fun count(): Flow<Int>

    @Query("DELETE FROM summaries WHERE day = :day")
    suspend fun deleteDay(day: String)

    @Query("DELETE FROM summaries")
    suspend fun deleteAll()

    /**
     * Days with messages written before [beforeMillis] that have no summary yet, oldest
     * first. Days use the phone's time zone; the storage limit's test notes don't count.
     */
    @Query(
        """
        SELECT DISTINCT strftime('%Y-%m-%d', timeMillis / 1000, 'unixepoch', 'localtime') AS day
        FROM messages
        WHERE timeMillis < :beforeMillis
          AND NOT (IFNULL(context, '') = 'Test' AND text LIKE 'Test note %')
          AND role != 'knowledge'
          AND strftime('%Y-%m-%d', timeMillis / 1000, 'unixepoch', 'localtime') NOT IN (SELECT day FROM summaries)
        ORDER BY day
        LIMIT :limit
        """
    )
    suspend fun unsummarizedDays(beforeMillis: Long, limit: Int): List<String>

    /** One day's messages, oldest first. */
    @Query(
        """
        SELECT * FROM messages
        WHERE strftime('%Y-%m-%d', timeMillis / 1000, 'unixepoch', 'localtime') = :day
          AND NOT (IFNULL(context, '') = 'Test' AND text LIKE 'Test note %')
          AND role != 'knowledge'
        ORDER BY timeMillis, id
        """
    )
    suspend fun messagesOn(day: String): List<MemoryMessage>

    /** Full-text search of summaries; build the query with [MemoryDatabase.summarySearchQuery]. */
    @RawQuery
    suspend fun searchNow(query: SupportSQLiteQuery): List<DaySummary>
}
