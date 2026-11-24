package com.martin.ruby.briefing

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The heads-up segment's facts: practical notes for the drive or the day, worked out on the
 * phone from the forecast and calendar, most important first. Claude says the one or two
 * that matter; an empty list means there's nothing worth a heads-up today.
 */
object HeadsUp {

    /** At most this many notes go to Claude. */
    const val MAX_NOTES = 3

    fun notes(
        forecast: Forecast?,
        events: List<CalendarEvent>?,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<String> {
        val notes = mutableListOf<String>()
        if (forecast != null) {
            val f = forecast
            val drive = f.commute(now)
            val restOfDay = f.between(now.plusHours(Forecast.COMMUTE_HOURS), now.toLocalDate().atTime(21, 0))
            val sinceMidnight = f.between(now.toLocalDate().atStartOfDay(), now.plusHours(Forecast.COMMUTE_HOURS))
            val freezing = if (f.unit == "F") 34 else 1
            val coldestDrive = drive.minOfOrNull { it.temp }

            // Ice: snow or freezing rain now, or near freezing after something fell overnight.
            when {
                drive.any { Forecast.isSnowOrIce(it.code) } ->
                    notes += "${Forecast.describe(drive.first { Forecast.isSnowOrIce(it.code) }.code).replaceFirstChar { it.uppercase() }} during the drive: roads may be slick."
                coldestDrive != null && coldestDrive <= freezing && sinceMidnight.any { it.precipitation >= 0.1 } ->
                    notes += "Near freezing (${coldestDrive}°${f.unit}) after overnight rain or snow: watch for slick roads and black ice."
                coldestDrive != null && coldestDrive <= freezing - 2 ->
                    notes += "Below freezing this morning (${coldestDrive}°${f.unit}): there may be frost on the windshield."
            }
            // Fog.
            val foggy = drive.firstOrNull { Forecast.isFog(it.code) || (it.visibility != null && it.visibility < 1000) }
            if (foggy != null) notes += "Fog likely during the drive" +
                (foggy.visibility?.takeIf { it < 1000 }?.let { ", with visibility down to about $it meters." } ?: ".")
            // Rain now, or later.
            val wetDrive = drive.maxOfOrNull { it.rainChance ?: 0 } ?: 0
            if (wetDrive >= 50 && drive.none { Forecast.isSnowOrIce(it.code) }) {
                notes += "Rain likely during the drive ($wetDrive% chance)."
            } else {
                restOfDay.firstOrNull { (it.rainChance ?: 0) >= 50 }?.let { h ->
                    notes += "Rain likely from about ${clock(h.time)} (${h.rainChance}% chance): an umbrella would help."
                }
            }
            // Wind, heat, bitter cold.
            val gusts = (drive + restOfDay).mapNotNull { it.gusts }.maxOrNull()
            if (gusts != null && gusts >= (if (f.windUnit == "mph") 40 else 64)) notes += "Strong gusts up to $gusts ${f.windUnit} today."
            if (f.high >= (if (f.unit == "F") 95 else 35)) notes += "Hot today, up to ${f.high}°${f.unit}: take water."
            if (coldestDrive != null && coldestDrive <= (if (f.unit == "F") 15 else -10)) notes += "Bitter cold this morning, ${coldestDrive}°${f.unit}."
        }
        // An early start on the calendar.
        events?.filter { !it.allDay }?.minByOrNull { it.startMillis }?.let { first ->
            val starts = java.time.Instant.ofEpochMilli(first.startMillis).atZone(zone).toLocalDateTime()
            if (starts.isAfter(now) && starts.isBefore(now.plusMinutes(90))) {
                notes += "First event soon: ${first.title.ifBlank { "an event" }} at ${clock(starts)}."
            }
        }
        return notes.take(MAX_NOTES)
    }

    private fun clock(t: LocalDateTime) = BriefingText.clock(t)
}
