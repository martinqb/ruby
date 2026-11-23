package com.martin.ruby.reminders

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A reminder: at a time ([dueMillis]), or on arriving at a saved place ([place]). Exactly
 * one of the two is set. Kept in the memory database, so it's encrypted and backed up.
 */
@Entity(tableName = "reminders", indices = [Index("state")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    /** When to remind (wall-clock time), for a time reminder. */
    val dueMillis: Long? = null,
    /** The saved place's name, for an arrival reminder. */
    val place: String? = null,
    val createdMillis: Long,
    /** [STATE_PENDING], [STATE_FIRED], [STATE_DONE] or [STATE_CANCELLED]. */
    val state: String = STATE_PENDING,
    /** When the notification went up, once it has. */
    val firedMillis: Long? = null,
) {
    companion object {
        /** Waiting for its time or place. */
        const val STATE_PENDING = "pending"

        /** The notification went up; the user hasn't marked it done. */
        const val STATE_FIRED = "fired"
        const val STATE_DONE = "done"
        const val STATE_CANCELLED = "cancelled"
    }
}

@Dao
interface ReminderDao {

    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): Reminder?

    @Query("SELECT * FROM reminders WHERE state = 'pending' ORDER BY dueMillis IS NULL, dueMillis, id")
    suspend fun pendingNow(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE state = 'pending' AND place = :place COLLATE NOCASE")
    suspend fun pendingAt(place: String): List<Reminder>

    /** Waiting and fired-but-not-done first, then the most recent finished ones. */
    @Query(
        """
        SELECT * FROM reminders WHERE state IN ('pending', 'fired')
           OR (state IN ('done', 'cancelled') AND createdMillis > :since)
        ORDER BY CASE state WHEN 'fired' THEN 0 WHEN 'pending' THEN 1 ELSE 2 END,
                 dueMillis IS NULL, dueMillis, id DESC
        """
    )
    fun forScreen(since: Long): Flow<List<Reminder>>

    @Query("UPDATE reminders SET state = :state, firedMillis = COALESCE(:firedMillis, firedMillis) WHERE id = :id")
    suspend fun setState(id: Long, state: String, firedMillis: Long? = null)

    @Query("UPDATE reminders SET state = 'pending', dueMillis = :dueMillis WHERE id = :id")
    suspend fun reschedule(id: Long, dueMillis: Long)
}
