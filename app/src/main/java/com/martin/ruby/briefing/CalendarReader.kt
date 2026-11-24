package com.martin.ruby.briefing

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Today's events from the phone's calendars (needs READ_CALENDAR). Read only. */
object CalendarReader {

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events on [date] in visible calendars, leaving out ones the user declined. Null without permission. */
    fun eventsOn(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent>? {
        if (!hasPermission(context)) return null
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val events = mutableListOf<CalendarEvent>()
        context.contentResolver.query(
            uri, projection, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                val allDay = c.getInt(3) == 1
                val begin = c.getLong(1)
                // All-day events are stored at midnight UTC; keep only today's.
                if (allDay && Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate() != date) continue
                events += CalendarEvent(
                    title = c.getString(0).orEmpty(),
                    startMillis = begin,
                    endMillis = c.getLong(2),
                    allDay = allDay,
                    location = c.getString(4),
                )
            }
        }
        return events
    }
}
