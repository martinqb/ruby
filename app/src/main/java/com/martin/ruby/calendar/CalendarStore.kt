package com.martin.ruby.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.martin.ruby.data.dataStore
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * The phone's calendars, through Android's Calendar Provider: the shared store that Samsung
 * Calendar, Google Calendar and other calendar apps all read and sync, so an event Ruby saves
 * shows up in them (and syncs, for Google) as if added there. Reading needs READ_CALENDAR;
 * saving, changing and deleting need WRITE_CALENDAR.
 */
class CalendarStore(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val defaultKey = longPreferencesKey("calendar_default_id")

    fun canRead() = has(Manifest.permission.READ_CALENDAR)
    fun canWrite() = has(Manifest.permission.WRITE_CALENDAR)

    private fun has(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    /** Calendars Ruby can add to (the user can at least contribute), visible ones only. */
    suspend fun calendars(): List<PhoneCalendar> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val out = mutableListOf<PhoneCalendar>()
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.IS_PRIMARY,
            ),
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += PhoneCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getInt(4) == 1)
            }
        }
        out
    }

    suspend fun defaultId(): Long? = app.dataStore.data.first()[defaultKey]

    suspend fun setDefault(id: Long) {
        app.dataStore.edit { it[defaultKey] = id }
    }

    /** Events between [from] and [to] (epoch millis) in visible calendars, declined ones left out. */
    suspend fun events(from: Long, to: Long): List<CalEvent> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = mutableListOf<CalEvent>()
        resolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1",
            null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                out += CalEvent(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getLong(5), c.getString(6))
            }
        }
        out
    }

    /** Does [action]. Returns the new event's id for an add. Throws [SecurityException] without WRITE_CALENDAR. */
    suspend fun apply(action: CalendarAction): Long? = withContext(Dispatchers.IO) {
        when (action) {
            is CalendarAction.Add -> {
                val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values(action.draft))
                    ?: throw IllegalStateException("The calendar didn't take the event.")
                ContentUris.parseId(uri).also { SensingLog.add("Calendar: added \"${action.draft.title}\" (event $it).") }
            }
            is CalendarAction.Change -> {
                val n = resolver.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, action.event.eventId), values(action.draft), null, null)
                if (n == 0) throw IllegalStateException("That event isn't there any more.")
                SensingLog.add("Calendar: changed event ${action.event.eventId}.")
                null
            }
            is CalendarAction.Delete -> {
                val n = resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, action.event.eventId), null, null)
                if (n == 0) throw IllegalStateException("That event isn't there any more.")
                SensingLog.add("Calendar: deleted event ${action.event.eventId}.")
                null
            }
        }
    }

    private fun values(d: EventDraft) = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, d.calendarId)
        put(CalendarContract.Events.TITLE, d.title)
        put(CalendarContract.Events.DTSTART, d.startMillis)
        put(CalendarContract.Events.DTEND, d.endMillis)
        put(CalendarContract.Events.ALL_DAY, if (d.allDay) 1 else 0)
        // All-day events are kept in UTC, as calendar apps expect.
        put(CalendarContract.Events.EVENT_TIMEZONE, if (d.allDay) "UTC" else TimeZone.getDefault().id)
        d.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
    }
}
