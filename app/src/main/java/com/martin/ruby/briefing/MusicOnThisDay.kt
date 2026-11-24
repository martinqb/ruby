package com.martin.ruby.briefing

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** A moment in music history on today's date, from Wikipedia. [kind] is "event" or "birth". */
data class MusicMoment(val year: Int, val text: String, val kind: String)

/**
 * Music on this day, from Wikipedia's free "On this day" feed. Only the date is sent. The
 * facts come from Wikipedia, so Claude picks one rather than recalling one (which could get
 * a date wrong).
 */
object MusicOnThisDay {

    /** At most this many go to Claude to pick from. */
    const val MAX_CANDIDATES = 10

    fun url(date: LocalDate): String =
        "https://en.wikipedia.org/api/rest_v1/feed/onthisday/all/%02d/%02d".format(date.monthValue, date.dayOfMonth)

    /** Fetches today's music moments. Blocking: call off the main thread. Throws [IOException]. */
    fun fetch(date: LocalDate): List<MusicMoment> {
        val connection = URL(url(date)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            // Wikipedia asks API users to say who they are; no personal details.
            connection.setRequestProperty("User-Agent", "Ruby-Android/1.0 (personal assistant app)")
            val code = connection.responseCode
            if (code != 200) throw IOException("Wikipedia returned $code")
            return parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Music, as Wikipedia's short page descriptions put it ("1971 studio album by Led Zeppelin",
     * "British rock band", "American singer"). Matched on those, not the event's own text, where
     * words like "recorded" or "single" often mean something else.
     */
    private val MUSIC = Regex(
        "\\b(album|EP|single|song|songs|band|rock group|pop group|vocal group|girl group|boy band|singer|singer-songwriter|songwriter|musician|rapper|" +
            "composer|guitarist|drummer|bassist|pianist|violinist|cellist|saxophonist|trumpeter|keyboardist|" +
            "vocalist|DJ|conductor|orchestra|symphony|opera|concerto|musical|music festival|concert|" +
            "record label|music)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Music history at its best: these come first. */
    private val STRONG = Regex(
        "\\b(album|single|recorded|recording|released|concert|performs|performed|premiere|premiered|debut|" +
            "tour|chart|charts|Billboard|Grammy|number one|No\\. 1|gig|festival)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A morning briefing wants a cool moment, not a sad one: deaths and disasters are left out. */
    private val GRIM = Regex(
        "\\b(dies|died|death|dead|killing|killed|kills|murder|murdered|shot|shooting|assassinat\\w*|crash|crashes|" +
            "bomb\\w*|massacre|explosion|earthquake|war|attack|suicide|overdose|landslide|flood|fire|" +
            "destroys|destroyed|disaster|sinks|sank)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Reads the feed and keeps the music ones: events first (Wikipedia's picks first), then musicians' birthdays. */
    fun parse(json: String): List<MusicMoment> = try {
        val root = JSONObject(json)
        val out = LinkedHashMap<String, MusicMoment>() // by text, so "selected" and "events" don't repeat
        for ((section, kind) in listOf("selected" to "event", "events" to "event", "births" to "birth")) {
            val items = root.optJSONArray(section) ?: continue
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val text = item.optString("text").trim()
                val year = item.optInt("year", 0)
                if (text.isEmpty() || year == 0) continue
                val pages = item.optJSONArray("pages")
                val descriptions = (0 until (pages?.length() ?: 0)).joinToString(" ") { p ->
                    pages!!.getJSONObject(p).optString("description")
                }
                // Births: the person's own description must be musical ("American singer"), not
                // just a page linked from it. Events: any linked page about music (the album, the band).
                val about = if (kind == "birth") pages?.optJSONObject(0)?.optString("description").orEmpty() else descriptions
                if (MUSIC.containsMatchIn(about) && !GRIM.containsMatchIn(text)) out.putIfAbsent(text, MusicMoment(year, text, kind))
            }
        }
        // Strong music events first (albums, concerts...), keeping Wikipedia's order otherwise.
        out.values.sortedBy { if (it.kind == "event" && STRONG.containsMatchIn(it.text)) 0 else 1 }
    } catch (e: org.json.JSONException) {
        throw IOException("Wikipedia's answer couldn't be read", e)
    }

    /** The ones to offer Claude: events before birthdays, then the rest, up to [MAX_CANDIDATES]. */
    fun candidates(moments: List<MusicMoment>): List<MusicMoment> =
        (moments.filter { it.kind == "event" } + moments.filter { it.kind == "birth" }).take(MAX_CANDIDATES)
}
