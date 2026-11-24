package com.martin.ruby.briefing

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** One hour of the forecast. */
data class ForecastHour(
    val time: LocalDateTime,
    val temp: Int,
    /** Chance of rain or snow, percent. */
    val rainChance: Int?,
    /** Rain or snow in the hour, mm. */
    val precipitation: Double,
    val code: Int,
    /** Cloud cover, percent. */
    val cloud: Int?,
    /** Strongest gusts, in [Forecast.windUnit]. */
    val gusts: Int?,
    /** How far you can see, meters. */
    val visibility: Int?,
)

/**
 * Today's weather for the briefing: now, the day, and hour by hour for today and tomorrow
 * (tomorrow's early hours cover tonight's sky). Temperatures in [unit] ("F" or "C"), all
 * times local to the forecast place.
 */
data class Forecast(
    val unit: String,
    val windUnit: String,
    val nowTemp: Int,
    val nowCode: Int,
    val high: Int,
    val low: Int,
    val dayCode: Int,
    val rainChance: Int?,
    val sunrise: LocalDateTime?,
    val sunset: LocalDateTime?,
    val hours: List<ForecastHour>,
) {
    val nowSky get() = describe(nowCode)
    val daySky get() = describe(dayCode)

    /** The hours from [from] (its hour included) until [until]. */
    fun between(from: LocalDateTime, until: LocalDateTime): List<ForecastHour> {
        val start = from.withMinute(0).withSecond(0).withNano(0)
        return hours.filter { !it.time.isBefore(start) && it.time.isBefore(until) }
    }

    /** The hours of the drive: those overlapping the next two hours (7:42 gives 7, 8 and 9). */
    fun commute(now: LocalDateTime) = between(now, now.plusHours(COMMUTE_HOURS))

    companion object {
        const val COMMUTE_HOURS = 2L

        /** Words for the WMO weather codes Open-Meteo uses. */
        fun describe(code: Int): String = when (code) {
            0 -> "clear"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "foggy"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71 -> "light snow"
            73 -> "snow"
            75 -> "heavy snow"
            77 -> "snow grains"
            80, 81 -> "rain showers"
            82 -> "heavy rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorms"
            96, 99 -> "thunderstorms with hail"
            else -> "mixed weather"
        }

        fun isSnowOrIce(code: Int) = code in setOf(56, 57, 66, 67, 71, 73, 75, 77, 85, 86)
        fun isFog(code: Int) = code == 45 || code == 48
    }
}

/**
 * The forecast from Open-Meteo (open-meteo.com): free, no account or key. Only a rough
 * location is sent: the coordinates rounded to two decimals, about 1 km.
 */
object OpenMeteo {

    /** Rounds a coordinate to two decimals (about 1 km), so the exact spot isn't sent. */
    fun rough(coordinate: Double): String = String.format(Locale.US, "%.2f", coordinate)

    /** Fahrenheit and mph where people use them (the US and a few others), else Celsius and km/h. */
    fun unitFor(locale: Locale = Locale.getDefault()): String =
        if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) "F" else "C"

    fun url(latitude: Double, longitude: Double, unit: String): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${rough(latitude)}&longitude=${rough(longitude)}" +
            "&current=temperature_2m,weather_code" +
            "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,cloud_cover,wind_gusts_10m,visibility" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset" +
            "&temperature_unit=${if (unit == "F") "fahrenheit" else "celsius"}" +
            "&wind_speed_unit=${if (unit == "F") "mph" else "kmh"}&timezone=auto&forecast_days=2"

    /** Fetches the forecast. Blocking: call off the main thread. Throws [IOException]. */
    fun fetch(latitude: Double, longitude: Double, unit: String = unitFor()): Forecast {
        val connection = URL(url(latitude, longitude, unit)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "Ruby-Android")
            val code = connection.responseCode
            if (code != 200) throw IOException("Open-Meteo returned $code")
            return parse(connection.inputStream.bufferedReader().use { it.readText() }, unit)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads Open-Meteo's answer. Throws [IOException] if it isn't what's expected. */
    fun parse(json: String, unit: String): Forecast = try {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        fun ints(name: String): (Int) -> Int? {
            val a = hourly.optJSONArray(name)
            return { i -> if (a == null || a.isNull(i)) null else a.getDouble(i).roundToInt() }
        }
        val temp = ints("temperature_2m")
        val rain = ints("precipitation_probability")
        val codes = ints("weather_code")
        val cloud = ints("cloud_cover")
        val gusts = ints("wind_gusts_10m")
        val visibility = ints("visibility")
        val precipitation = hourly.optJSONArray("precipitation")
        val hours = (0 until times.length()).map { i ->
            ForecastHour(
                time = LocalDateTime.parse(times.getString(i)),
                temp = temp(i) ?: 0,
                rainChance = rain(i),
                precipitation = if (precipitation == null || precipitation.isNull(i)) 0.0 else precipitation.getDouble(i),
                code = codes(i) ?: 0,
                cloud = cloud(i),
                gusts = gusts(i),
                visibility = visibility(i),
            )
        }
        fun first(name: String): JSONArray = daily.getJSONArray(name)
        val rainMax = daily.optJSONArray("precipitation_probability_max")
        Forecast(
            unit = unit,
            windUnit = if (unit == "F") "mph" else "km/h",
            nowTemp = current.getDouble("temperature_2m").roundToInt(),
            nowCode = current.getInt("weather_code"),
            high = first("temperature_2m_max").getDouble(0).roundToInt(),
            low = first("temperature_2m_min").getDouble(0).roundToInt(),
            dayCode = first("weather_code").getInt(0),
            rainChance = if (rainMax == null || rainMax.isNull(0)) null else rainMax.getInt(0),
            sunrise = daily.optJSONArray("sunrise")?.optString(0)?.takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
            sunset = daily.optJSONArray("sunset")?.optString(0)?.takeIf { it.isNotEmpty() }?.let(LocalDateTime::parse),
            hours = hours,
        )
    } catch (e: org.json.JSONException) {
        throw IOException("Open-Meteo's answer couldn't be read", e)
    } catch (e: java.time.format.DateTimeParseException) {
        throw IOException("Open-Meteo's times couldn't be read", e)
    }
}
