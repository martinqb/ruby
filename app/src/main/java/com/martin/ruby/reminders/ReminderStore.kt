package com.martin.ruby.reminders

import android.content.Context
import com.martin.ruby.log.SensingLog
import com.martin.ruby.memory.MemoryDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** Adding, firing and finishing reminders. Everything else in the app goes through here. */
class ReminderStore(context: Context) {

    private val appContext = context.applicationContext

    private suspend fun dao(): ReminderDao =
        withContext(Dispatchers.IO) { MemoryDatabase.get(appContext).reminderDao() }

    /** For the Reminders screen: open ones, plus finished ones from the last week. */
    fun forScreen(): Flow<List<Reminder>> = flow {
        val dao = try {
            dao()
        } catch (e: Exception) {
            emit(emptyList())
            return@flow
        }
        emitAll(dao.forScreen(System.currentTimeMillis() - WEEK_MILLIS))
    }.flowOn(Dispatchers.IO)

    suspend fun pending(): List<Reminder> = dao().pendingNow()

    suspend fun get(id: Long): Reminder? = dao().get(id)

    /** Saves a reminder for a time ([dueMillis]) or a place, and schedules it. */
    suspend fun add(text: String, dueMillis: Long?, place: String?): Reminder {
        require((dueMillis == null) != (place == null)) { "A reminder needs a time or a place, not both." }
        val reminder = Reminder(text = text, dueMillis = dueMillis, place = place, createdMillis = System.currentTimeMillis())
        val saved = reminder.copy(id = dao().insert(reminder))
        ReminderAlarms.schedule(appContext, saved)
        SensingLog.add("Reminder ${saved.id} set: \"$text\" " + if (place != null) "at $place." else "for ${ReminderText.time(dueMillis!!)}.")
        return saved
    }

    /** Cancels a reminder that hasn't gone off. Returns false if it wasn't waiting. */
    suspend fun cancel(id: Long): Boolean {
        val reminder = dao().get(id) ?: return false
        if (reminder.state != Reminder.STATE_PENDING && reminder.state != Reminder.STATE_FIRED) return false
        ReminderAlarms.cancel(appContext, id)
        ReminderNotifier.dismiss(appContext, id)
        dao().setState(id, Reminder.STATE_CANCELLED)
        SensingLog.add("Reminder $id cancelled.")
        return true
    }

    suspend fun done(id: Long) {
        ReminderAlarms.cancel(appContext, id)
        ReminderNotifier.dismiss(appContext, id)
        dao().setState(id, Reminder.STATE_DONE)
    }

    suspend fun snooze(id: Long, minutes: Int = ReminderNotifier.SNOOZE_MINUTES) {
        val due = System.currentTimeMillis() + minutes * 60_000L
        dao().reschedule(id, due)
        ReminderNotifier.dismiss(appContext, id)
        dao().get(id)?.let { ReminderAlarms.schedule(appContext, it) }
    }

    /** The alarm for [id] went off: show it, unless it was cancelled or already shown. */
    suspend fun fire(id: Long, late: Boolean = false) {
        val reminder = dao().get(id) ?: return
        if (reminder.state != Reminder.STATE_PENDING) return
        dao().setState(id, Reminder.STATE_FIRED, System.currentTimeMillis())
        ReminderNotifier.show(appContext, reminder, late)
        SensingLog.add("Reminder $id went off.")
    }

    /** The decision engine says the user just arrived at [place]. */
    suspend fun arrivedAt(place: String) {
        dao().pendingAt(place).forEach { fire(it.id) }
    }

    /**
     * Schedules every waiting time reminder again, e.g. after a restart (Android forgets
     * alarms then) or a restore. Ones whose time passed meanwhile go off now.
     */
    suspend fun rescheduleAll() {
        val now = System.currentTimeMillis()
        dao().pendingNow().filter { it.dueMillis != null }.forEach { reminder ->
            if (reminder.dueMillis!! <= now) fire(reminder.id, late = now - reminder.dueMillis > LATE_MILLIS)
            else ReminderAlarms.schedule(appContext, reminder)
        }
    }

    private companion object {
        const val WEEK_MILLIS = 7 * 24 * 3_600_000L

        /** More than this late counts as missed (the phone was off, say). */
        const val LATE_MILLIS = 5 * 60_000L
    }
}
