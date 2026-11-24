package com.martin.ruby.briefing

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.moonPhase
import io.github.cosinekitty.astronomy.searchAltitude
import io.github.cosinekitty.astronomy.searchRiseSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** A planet worth catching tonight: highest at [best], [altitude]° up toward [direction]. */
data class PlanetView(val name: String, val best: LocalDateTime, val altitude: Int, val direction: String, val magnitude: Double) {
    /** Easy to see by eye (Mercury to Saturn). Uranus (about 5.7) and Neptune need binoculars or a telescope. */
    val nakedEye get() = magnitude < 4.5
}

/** Tonight's sky for a backyard telescope, worked out on the phone. */
data class SkyTonight(
    /** When it gets properly dark (the Sun 12° below the horizon) and light again. */
    val darkFrom: LocalDateTime,
    val darkUntil: LocalDateTime,
    val moonPhase: String,
    /** Percent of the Moon lit. */
    val moonLit: Int,
    val moonrise: LocalDateTime?,
    val moonset: LocalDateTime?,
    /** Cloud cover each evening hour, percent, if the forecast had it. */
    val clouds: List<Pair<LocalDateTime, Int>>,
    val planets: List<PlanetView>,
) {
    /** "good", "fair" or "poor": how much of the evening looks clear (30% cloud or less). */
    val clearChance: String?
        get() {
            if (clouds.isEmpty()) return null
            val share = clouds.count { it.second <= CLEAR_CLOUD } / clouds.size.toDouble()
            return when {
                share >= 0.7 -> "good"
                share >= 0.35 -> "fair"
                else -> "poor"
            }
        }

    /** The longest clear stretch of the evening, if any: first and last clear hour. */
    val clearestStretch: Pair<LocalDateTime, LocalDateTime>?
        get() {
            var best: Pair<Int, Int>? = null
            var start = -1
            for (i in clouds.indices) {
                if (clouds[i].second <= CLEAR_CLOUD) {
                    if (start < 0) start = i
                    if (best == null || i - start > best.second - best.first) best = start to i
                } else {
                    start = -1
                }
            }
            return best?.let { clouds[it.first].first to clouds[it.second].first.plusHours(1) }
        }

    companion object {
        const val CLEAR_CLOUD = 30

        /** The evening the briefing covers: from dark until this many hours later. */
        const val EVENING_HOURS = 6L

        private val PLANETS = listOf(
            Body.Mercury, Body.Venus, Body.Mars, Body.Jupiter, Body.Saturn, Body.Uranus, Body.Neptune,
        )

        /** The phase's name from the Moon's phase angle (0 new, 90 first quarter, 180 full, 270 last quarter). */
        fun phaseName(angle: Double): String = when (((angle % 360) + 360) % 360) {
            in 0.0..<11.25, in 348.75..360.0 -> "new moon"
            in 11.25..<78.75 -> "waxing crescent"
            in 78.75..<101.25 -> "first quarter"
            in 101.25..<168.75 -> "waxing gibbous"
            in 168.75..<191.25 -> "full moon"
            in 191.25..<258.75 -> "waning gibbous"
            in 258.75..<281.25 -> "last quarter"
            else -> "waning crescent"
        }

        fun compass(azimuth: Double): String =
            listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")[
                (((azimuth % 360) + 360) % 360 / 45.0).roundToInt() % 8
            ]

        /**
         * Tonight's sky over ([latitude], [longitude]) on [date], in [zone]. [forecast] adds
         * the evening's cloud cover. Takes a few milliseconds; nothing is sent anywhere.
         */
        fun compute(latitude: Double, longitude: Double, date: LocalDate, zone: ZoneId, forecast: Forecast?): SkyTonight {
            val observer = Observer(latitude, longitude, 0.0)
            fun time(t: LocalDateTime) = Time.fromMillisecondsSince1970(t.atZone(zone).toInstant().toEpochMilli())
            fun local(t: Time) = Instant.ofEpochMilli(t.toMillisecondsSince1970()).atZone(zone).toLocalDateTime()

            val noon = time(date.atTime(12, 0))
            val darkFrom = searchAltitude(Body.Sun, observer, Direction.Set, noon, 1.0, -12.0)?.let(::local)
                ?: date.atTime(20, 0)
            val darkUntil = searchAltitude(Body.Sun, observer, Direction.Rise, time(darkFrom), 1.0, -12.0)?.let(::local)
                ?: date.plusDays(1).atTime(5, 0)
            val eveningEnd = minOf(darkUntil, darkFrom.plusHours(EVENING_HOURS))

            val middle = time(darkFrom.plusHours(2))
            val moonrise = searchRiseSet(Body.Moon, observer, Direction.Rise, noon, 1.0)?.let(::local)
            val moonset = searchRiseSet(Body.Moon, observer, Direction.Set, noon, 1.0)?.let(::local)

            // Each planet's highest point during the evening, checked every 20 minutes.
            val samples = generateSequence(darkFrom) { it.plusMinutes(20) }.takeWhile { !it.isAfter(eveningEnd) }.toList()
            val planets = PLANETS.mapNotNull { body ->
                var best: Triple<LocalDateTime, Double, Double>? = null
                for (s in samples) {
                    val t = time(s)
                    val eq = equator(body, t, observer, EquatorEpoch.OfDate, Aberration.Corrected)
                    val hor = horizon(t, observer, eq.ra, eq.dec, Refraction.Normal)
                    if (best == null || hor.altitude > best.second) best = Triple(s, hor.altitude, hor.azimuth)
                }
                val b = best ?: return@mapNotNull null
                if (b.second < 10.0) return@mapNotNull null // too low to bother with
                PlanetView(
                    name = body.name,
                    best = b.first,
                    altitude = b.second.roundToInt(),
                    direction = compass(b.third),
                    magnitude = illumination(body, time(b.first)).mag,
                )
            }.sortedBy { it.magnitude }

            val clouds = forecast?.between(darkFrom, eveningEnd)?.mapNotNull { h -> h.cloud?.let { h.time to it } }.orEmpty()
            return SkyTonight(
                darkFrom = darkFrom,
                darkUntil = darkUntil,
                moonPhase = phaseName(moonPhase(middle)),
                moonLit = (illumination(Body.Moon, middle).phaseFraction * 100).roundToInt(),
                moonrise = moonrise,
                moonset = moonset,
                clouds = clouds,
                planets = planets,
            )
        }
    }
}
