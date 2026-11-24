package com.martin.ruby.briefing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDateTime
import java.util.Locale

/** Step 3: the weather for "today at a glance", from Open-Meteo. */
class ForecastTest {

    companion object {
        /**
         * Open-Meteo's answer for 2026-11-24 and 25: 31°F and overcast at 7 AM, rain from 3 PM,
         * clearing in the evening. Built the way Open-Meteo shapes it.
         */
        fun json(
            temp: (Int) -> Double = { h -> if (h < 24) 30.0 + (if (h in 6..15) h - 6 else 9) else 28.0 },
            rain: (Int) -> Int = { h -> if (h in 15..18) 70 else 10 },
            code: (Int) -> Int = { h -> if (h in 15..18) 63 else 3 },
            cloud: (Int) -> Int = { h -> if (h in 15..19) 95 else if (h in 20..23) 15 else 60 },
            precipitation: (Int) -> Double = { h -> if (h in 15..18) 1.2 else 0.0 },
            gusts: (Int) -> Int = { 18 },
            visibility: (Int) -> Int = { 24000 },
        ): String {
            val hours = 0 until 48
            fun time(h: Int) = LocalDateTime.of(2026, 11, 24, 0, 0).plusHours(h.toLong()).toString()
            return JSONObject()
                .put("current", JSONObject().put("time", "2026-11-24T07:30").put("temperature_2m", 31.2).put("weather_code", 3))
                .put(
                    "hourly", JSONObject()
                        .put("time", JSONArray(hours.map(::time)))
                        .put("temperature_2m", JSONArray(hours.map(temp)))
                        .put("precipitation_probability", JSONArray(hours.map(rain)))
                        .put("precipitation", JSONArray(hours.map(precipitation)))
                        .put("weather_code", JSONArray(hours.map(code)))
                        .put("cloud_cover", JSONArray(hours.map(cloud)))
                        .put("wind_gusts_10m", JSONArray(hours.map(gusts)))
                        .put("visibility", JSONArray(hours.map(visibility)))
                )
                .put(
                    "daily", JSONObject()
                        .put("time", JSONArray(listOf("2026-11-24", "2026-11-25")))
                        .put("weather_code", JSONArray(listOf(63, 3)))
                        .put("temperature_2m_max", JSONArray(listOf(44.6, 40.0)))
                        .put("temperature_2m_min", JSONArray(listOf(29.8, 27.0)))
                        .put("precipitation_probability_max", JSONArray(listOf(70, 10)))
                        .put("sunrise", JSONArray(listOf("2026-11-24T07:01", "2026-11-25T07:02")))
                        .put("sunset", JSONArray(listOf("2026-11-24T16:59", "2026-11-25T16:58")))
                )
                .toString()
        }

        fun forecast(json: String = json()) = OpenMeteo.parse(json, "F")
    }

    @Test
    fun readsOpenMeteosAnswer() {
        val f = forecast()
        assertEquals(31, f.nowTemp)
        assertEquals("overcast", f.nowSky)
        assertEquals(45, f.high)
        assertEquals(30, f.low)
        assertEquals("rain", f.daySky)
        assertEquals(70, f.rainChance)
        assertEquals(LocalDateTime.of(2026, 11, 24, 16, 59), f.sunset)
        assertEquals(48, f.hours.size)
        assertEquals("mph", f.windUnit)
        val three = f.hours[15]
        assertEquals(LocalDateTime.of(2026, 11, 24, 15, 0), three.time)
        assertEquals(70, three.rainChance)
        assertEquals(1.2, three.precipitation, 1e-9)
        assertEquals(95, three.cloud)
    }

    @Test
    fun theDrive_isTheNextTwoHours() {
        val f = forecast()
        val drive = f.commute(LocalDateTime.of(2026, 11, 24, 7, 42))
        assertEquals(listOf(7, 8, 9), drive.map { it.time.hour })
    }

    @Test
    fun onlyARoughLocationIsSent() {
        val url = OpenMeteo.url(39.099727, -94.578567, "F")
        assertTrue(url, url.contains("latitude=39.10&longitude=-94.58"))
        assertFalse(url.contains("39.0997"))
        assertTrue(url.contains("temperature_unit=fahrenheit") && url.contains("wind_speed_unit=mph"))
        assertTrue(OpenMeteo.url(51.5, -0.12, "C").contains("temperature_unit=celsius"))
        assertEquals("F", OpenMeteo.unitFor(Locale.US))
        assertEquals("C", OpenMeteo.unitFor(Locale.UK))
    }

    @Test
    fun weatherCodes_inWords() {
        assertEquals("clear", Forecast.describe(0))
        assertEquals("foggy", Forecast.describe(45))
        assertEquals("freezing rain", Forecast.describe(66))
        assertEquals("thunderstorms with hail", Forecast.describe(99))
        assertTrue(Forecast.isSnowOrIce(73) && !Forecast.isSnowOrIce(63))
    }

    @Test(expected = IOException::class)
    fun aBrokenAnswer_isAnError_notACrash() {
        OpenMeteo.parse("""{"current":{}}""", "F")
    }
}
