package com.martin.ruby.briefing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Step 6: music on this day, from Wikipedia's "On this day" feed. */
class MusicOnThisDayTest {

    private fun item(year: Int, text: String, vararg descriptions: String) = JSONObject()
        .put("year", year)
        .put("text", text)
        .put("pages", JSONArray(descriptions.map { JSONObject().put("title", "x").put("description", it) }))

    private val feed = JSONObject()
        .put(
            "selected", JSONArray(
                listOf(
                    item(1859, "Charles Darwin publishes On the Origin of Species.", "1859 book by Charles Darwin"),
                    item(1991, "Queen's Freddie Mercury dies the day after announcing his illness.", "British rock band"),
                )
            )
        )
        .put(
            "events", JSONArray(
                listOf(
                    item(1991, "Queen's Freddie Mercury dies the day after announcing his illness.", "British rock band"),
                    item(1966, "The Beatles begin recording Strawberry Fields Forever.", "Song by the Beatles"),
                    item(1963, "Lee Harvey Oswald is shot.", "American assassin"),
                    item(2001, "A plane crashes, killing a singer and two members of a band.", "Air disaster"),
                    item(1974, "Lucy is found, nicknamed after the Beatles song.", "Fossil", "1967 song by the Beatles"),
                    item(1248, "A landslide, one of the largest ever recorded, destroys five villages.", "Mountain in France"),
                    item(1980, "A single mother opens a record shop that tops the local chart.", "City in Ohio"),
                    item(2013, "An agreement is signed with the P5+1 countries.", "Group of countries"),
                    item(2009, "A cultural project is launched.", "Ethnic group native to the Balkans"),
                    item(1985, "Band Aid's single tops the UK chart.", "Charity single"),
                    item(2001, "A bill is signed into law.", "Act of Congress"),
                )
            )
        )
        .put(
            "births", JSONArray(
                listOf(
                    item(1942, "Billy Connolly, Scottish comedian", "Scottish comedian, musician and actor"),
                    item(1955, "Ian Botham, English cricketer", "English cricketer"),
                    item(1940, "A politician who once wrote a song", "American politician"),
                )
            )
        )
        .put("deaths", JSONArray(listOf(item(1991, "Freddie Mercury, British singer", "British singer"))))
        .toString()

    @Test
    fun keepsCoolMusicMoments_strongestFirst_noRepeats_noneSad() {
        val moments = MusicOnThisDay.parse(feed)
        assertEquals(
            listOf(
                // Records, charts and concerts first...
                MusicMoment(1966, "The Beatles begin recording Strawberry Fields Forever.", "event"),
                MusicMoment(1985, "Band Aid's single tops the UK chart.", "event"),
                // ...then other music moments, then musicians' birthdays.
                MusicMoment(1974, "Lucy is found, nicknamed after the Beatles song.", "event"),
                MusicMoment(1942, "Billy Connolly, Scottish comedian", "birth"),
            ),
            moments,
        )
        // Deaths and disasters, even of musicians, aren't morning material.
        assertTrue(moments.none { "dies" in it.text || "crash" in it.text })
        // Music words in the text alone ("recorded", "single", "chart") aren't enough.
        assertTrue(moments.none { "landslide" in it.text || "record shop" in it.text })
        assertTrue(moments.none { "P5+1" in it.text || "cultural project" in it.text })
    }

    @Test
    fun atMostTen_eventsBeforeBirthdays() {
        val many = (1..8).map { MusicMoment(1900 + it, "Birthday $it", "birth") } + (1..8).map { MusicMoment(2000 + it, "Album $it", "event") }
        val picked = MusicOnThisDay.candidates(many)
        assertEquals(MusicOnThisDay.MAX_CANDIDATES, picked.size)
        assertEquals(List(8) { "event" } + List(2) { "birth" }, picked.map { it.kind })
    }

    @Test
    fun onlyTheDateIsSent() {
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/feed/onthisday/all/11/24",
            MusicOnThisDay.url(LocalDate.of(2026, 11, 24)),
        )
        assertEquals("https://en.wikipedia.org/api/rest_v1/feed/onthisday/all/03/05", MusicOnThisDay.url(LocalDate.of(2026, 3, 5)))
    }
}
