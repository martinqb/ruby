package com.martin.ruby.radio

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A radio station. [stream] is its official public stream, played inside Ruby; a station
 * without one (iHeartMedia stations only stream in the iHeartRadio app) opens there instead,
 * by its [iHeartId], or in another radio app.
 */
data class Station(
    val id: String,
    val name: String,
    val city: String,
    val stream: String?,
    val iHeartId: Int?,
    /** Ways people say or write it, compared letters and digits only ("103.5" is "1035"). */
    val aliases: List<String> = emptyList(),
    val genre: String = "",
) {
    val playsInRuby: Boolean get() = stream != null
}

/** Stations Ruby knows without searching: found by search too (WGRR isn't in Radio Browser). Kept free of Android, for tests. */
object Stations {

    /** WGRR 103.5 Cincinnati: its official stream through Triton Digital (StreamTheWorld), mount WGRRFM. */
    val WGRR = Station(
        id = "wgrr",
        name = "WGRR 103.5",
        city = "Cincinnati, Ohio",
        stream = "https://playerservices.streamtheworld.com/api/livestream-redirect/WGRRFM.mp3",
        iHeartId = null,
        aliases = listOf("wgrr", "1035", "wgr r", "w g r r", "one oh three five", "one zero three point five"),
        genre = "Classic hits",
    )

    /**
     * WEBN 102.7 Cincinnati (iHeartMedia). Its only stream is iHeart's own player stream, not an
     * official public one, so it opens in the iHeartRadio app (station 1701).
     */
    val WEBN = Station(
        id = "webn",
        name = "WEBN 102.7",
        city = "Cincinnati, Ohio",
        stream = null,
        iHeartId = 1701,
        aliases = listOf("webn", "1027", "web n", "w e b n", "one oh two seven", "one zero two point seven"),
        genre = "Rock",
    )

    val CURATED = listOf(WGRR, WEBN)

    /** The curated stations matching a search ([text] in their name, aliases, city or genre). */
    fun curatedMatching(text: String): List<Station> {
        val t = squash(text)
        if (t.isEmpty()) return emptyList()
        return CURATED.filter { s -> (s.aliases + s.name + s.city + s.genre).any { t in squash(it) || squash(it) in t && squash(it).length >= 4 } }
    }

    /** Letters and digits only, lowercased: "103.5" and "one oh three five" compare as written. */
    fun squash(text: String) = text.lowercase().filter(Char::isLetterOrDigit)
}

/** A station saved by the user, under their own [label]. */
data class Preset(val label: String, val station: Station)

/**
 * The user's saved stations, in their order, and which plays for "play the radio". Saved on
 * the phone; new users start with none. Kept free of Android, for tests.
 */
data class Presets(val list: List<Preset> = emptyList(), val defaultId: String? = null) {

    /** The station "play the radio" plays: the chosen default, else the first. Null with no presets. */
    val default: Preset? get() = list.firstOrNull { it.station.id == defaultId } ?: list.firstOrNull()

    fun has(station: Station) = list.any { it.station.id == station.id }

    /** Adds [station] at the end (once); the first preset added becomes the default. */
    fun add(station: Station, label: String = station.name): Presets =
        if (has(station)) this else copy(list = list + Preset(label.trim().ifEmpty { station.name }, station), defaultId = defaultId ?: station.id)

    fun rename(id: String, label: String): Presets =
        copy(list = list.map { if (it.station.id == id) it.copy(label = label.trim().ifEmpty { it.station.name }) else it })

    /** Moves a preset up ([by] -1) or down (+1). */
    fun move(id: String, by: Int): Presets {
        val i = list.indexOfFirst { it.station.id == id }
        val j = i + by
        if (i < 0 || j !in list.indices) return this
        return copy(list = list.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun remove(id: String): Presets {
        val rest = list.filterNot { it.station.id == id }
        return Presets(rest, if (defaultId == id) rest.firstOrNull()?.station?.id else defaultId)
    }

    fun makeDefault(id: String): Presets = if (list.any { it.station.id == id }) copy(defaultId = id) else this

    /** The preset named in [text]: its label, or its station's name, call letters or frequency. */
    fun find(text: String): Preset? {
        val t = Stations.squash(text)
        return list.firstOrNull { p -> (listOf(p.label, p.station.name) + p.station.aliases).any { a -> Stations.squash(a).let { it.length >= 3 && it in t } } }
    }

    fun toJson(): String = JSONObject()
        .put("default", defaultId ?: JSONObject.NULL)
        .put("presets", JSONArray(list.map { p ->
            JSONObject().put("label", p.label).put("station", stationJson(p.station))
        }))
        .toString()

    companion object {
        /** Martin's profile: WGRR as default, and WEBN. Set up once on his phone, not built in for others. */
        val MARTIN = Presets().add(Stations.WGRR).add(Stations.WEBN)

        fun fromJson(json: String?): Presets {
            if (json.isNullOrBlank()) return Presets()
            return try {
                val o = JSONObject(json)
                val a = o.optJSONArray("presets") ?: JSONArray()
                val list = (0 until a.length()).mapNotNull { i ->
                    val p = a.getJSONObject(i)
                    station(p.optJSONObject("station"))?.let { Preset(p.optString("label").ifEmpty { it.name }, it) }
                }
                Presets(list, if (o.isNull("default")) null else o.optString("default"))
            } catch (e: JSONException) {
                Presets()
            }
        }

        private fun stationJson(s: Station) = JSONObject()
            .put("id", s.id).put("name", s.name).put("city", s.city).put("genre", s.genre)
            .put("stream", s.stream ?: JSONObject.NULL).put("iheart", s.iHeartId ?: JSONObject.NULL)
            .put("aliases", JSONArray(s.aliases))

        private fun station(o: JSONObject?): Station? {
            if (o == null) return null
            val id = o.optString("id").ifEmpty { return null }
            val aliases = o.optJSONArray("aliases")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            return Station(
                id = id,
                name = o.optString("name").ifEmpty { id },
                city = o.optString("city"),
                stream = if (o.isNull("stream")) null else o.optString("stream").ifEmpty { null },
                iHeartId = if (o.isNull("iheart")) null else o.optInt("iheart"),
                aliases = aliases,
                genre = o.optString("genre"),
            )
        }
    }
}

/**
 * Finding stations in the free Radio Browser directory (radio-browser.info). Only the search
 * words are sent. Stations whose only stream is iHeart's own player stream aren't played
 * inside Ruby; they open in iHeartRadio (the station number is in the stream address).
 */
object RadioBrowser {

    enum class By(val label: String, val param: String) {
        NAME("Name", "name"),
        PLACE("City or state", "state"),
        GENRE("Genre", "tag"),
    }

    /** Radio Browser's servers; tried in turn. */
    val SERVERS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info")

    const val LIMIT = 40

    fun url(server: String, by: By, text: String): String =
        "https://$server/json/stations/search?${by.param}=${java.net.URLEncoder.encode(text.trim(), "UTF-8")}" +
            "&hidebroken=true&order=clickcount&reverse=true&limit=$LIMIT"

    private val IHEART = Regex("ihrhls\\.com|iheart", RegexOption.IGNORE_CASE)
    private val IHEART_ID = Regex("/zc(\\d+)")

    /** Reads Radio Browser's answer into stations, without duplicates. */
    fun parse(json: String): List<Station> = try {
        val a = JSONArray(json)
        val seen = mutableSetOf<String>()
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val name = o.optString("name").trim().ifEmpty { return@mapNotNull null }
            val url = o.optString("url_resolved").ifEmpty { o.optString("url") }.trim()
            if (url.isEmpty() || !seen.add(Stations.squash(name) + "|" + url)) return@mapNotNull null
            val iHeart = IHEART.containsMatchIn(url)
            val place = listOf(o.optString("state"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            Station(
                id = "rb:" + o.optString("stationuuid").ifEmpty { url },
                name = name,
                city = place,
                stream = if (iHeart) null else url,
                iHeartId = if (iHeart) IHEART_ID.find(url)?.groupValues?.get(1)?.toIntOrNull() else null,
                aliases = listOf(name),
                genre = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", "),
            )
        }
    } catch (e: JSONException) {
        throw java.io.IOException("Radio Browser's answer couldn't be read", e)
    }
}

/** What a chat message asks of the radio, worked out on the phone (in either AI engine). */
sealed interface RadioCommand {
    data class Play(val station: Station) : RadioCommand
    data object Stop : RadioCommand
    /** "Play the radio" with no stations saved yet. */
    data object NoStations : RadioCommand
}

object RadioCommands {

    private val PLAY = Regex("^(please |can you |could you |hey ruby,? )?(play|put on|turn on|start|switch to|listen to|tune (in )?to)\\b")
    private val STOP = Regex(
        "^(please )?(stop|pause|turn off|switch off|shut off|kill|mute)( the)? (radio|music|station)( please)?$|^(radio|music) off$"
    )
    private val BARE_STOP = Regex("^(please )?(stop|pause|stop it|pause it|stop that|quiet|enough)( please)?$")

    /**
     * The radio command in [message], or null for an ordinary message. "Play [name]" plays a
     * saved preset (or a station Ruby knows); "play the radio" plays the default preset. A bare
     * "stop" only counts while something is playing ([playing]: Ruby's radio, or another app such
     * as iHeartRadio); otherwise it's left to the chat.
     */
    fun parse(message: String, playing: Boolean, presets: Presets = Presets()): RadioCommand? {
        val m = message.lowercase().trim().trimEnd('.', '!', '?').trim()
        if (STOP.matches(m) || (playing && BARE_STOP.matches(m))) return RadioCommand.Stop
        if (PLAY.containsMatchIn(m)) {
            val asked = PLAY.replaceFirst(m, "").trim()
            presets.find(asked)?.let { return RadioCommand.Play(it.station) }
            Stations.CURATED.firstOrNull { s -> s.aliases.any { Stations.squash(it) in Stations.squash(asked) } }?.let { return RadioCommand.Play(it) }
            if (Regex("^(the |my )?radio\\b").containsMatchIn(asked) || asked == "radio") {
                return presets.default?.let { RadioCommand.Play(it.station) } ?: RadioCommand.NoStations
            }
        }
        return null
    }

    const val NO_STATIONS = "You haven't saved any stations yet. Open Radio and tap Find a station to add some."

    /**
     * The note sent to the AI with each message: what the phone has really done with the radio
     * ([log], oldest first, like "9:10 AM: played WGRR 103.5 in Ruby"), so the AI never denies it.
     * Null if nothing has happened yet.
     */
    fun note(nowPlaying: String?, log: List<String>): String? {
        if (nowPlaying == null && log.isEmpty()) return null
        return "Radio (done by the phone; this really happened): " + (nowPlaying ?: "nothing playing in Ruby now") + "." +
            if (log.isEmpty()) "" else " Recently: " + log.joinToString("; ") + "."
    }

    /** What Ruby says after pressing pause for another app: did it stop? */
    fun pauseReply(app: Station?, stopped: Boolean): String = when {
        stopped && app != null -> "Paused ${app.name} in iHeartRadio."
        stopped -> "Paused."
        app != null -> "${app.name} is playing in iHeartRadio. Pause it there or with your steering wheel."
        else -> "Something's playing in another app. Pause it there or with your steering wheel."
    }

    /** After the morning briefing in the car: start the radio if that's on and she wasn't stopped. */
    fun startAfterBriefing(enabled: Boolean, inCar: Boolean, stoppedByUser: Boolean) = enabled && inCar && !stoppedByUser
}
