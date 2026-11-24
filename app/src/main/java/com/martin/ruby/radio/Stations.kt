package com.martin.ruby.radio

/**
 * A radio station Ruby knows. [stream] is its official public stream, played inside Ruby; a
 * station without one (iHeartMedia stations only stream in the iHeartRadio app) opens there
 * instead, by its [iHeartId].
 */
data class Station(
    val id: String,
    val name: String,
    val city: String,
    val stream: String?,
    val iHeartId: Int?,
    /** Ways people say or write it, compared letters and digits only ("103.5" is "1035"). */
    val aliases: List<String>,
) {
    val playsInRuby: Boolean get() = stream != null
}

/** Ruby's stations: WGRR is the default. Kept free of Android, for tests. */
object Stations {

    /** WGRR 103.5 Cincinnati: its official stream through Triton Digital (StreamTheWorld), mount WGRRFM. */
    val WGRR = Station(
        id = "wgrr",
        name = "WGRR 103.5",
        city = "Cincinnati",
        stream = "https://playerservices.streamtheworld.com/api/livestream-redirect/WGRRFM.mp3",
        iHeartId = null,
        aliases = listOf("wgrr", "1035", "wgr r", "w g r r", "one oh three five", "one zero three point five"),
    )

    /**
     * WEBN 102.7 Cincinnati (iHeartMedia). Its only stream is iHeart's own player stream, not an
     * official public one, so it opens in the iHeartRadio app (station 1701).
     */
    val WEBN = Station(
        id = "webn",
        name = "WEBN 102.7",
        city = "Cincinnati",
        stream = null,
        iHeartId = 1701,
        aliases = listOf("webn", "1027", "web n", "w e b n", "one oh two seven", "one zero two point seven"),
    )

    val ALL = listOf(WGRR, WEBN)
    val DEFAULT = WGRR

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    /** The station named in [text], if any. */
    fun find(text: String): Station? {
        val t = squash(text)
        return ALL.firstOrNull { s -> s.aliases.any { squash(it) in t } }
    }

    /** Letters and digits only, lowercased: "103.5" and "one oh three five" compare as written. */
    fun squash(text: String) = text.lowercase().filter(Char::isLetterOrDigit)
}

/** What a chat message asks of the radio, worked out on the phone (in either AI engine). */
sealed interface RadioCommand {
    data class Play(val station: Station) : RadioCommand
    data object Stop : RadioCommand
}

object RadioCommands {

    private val PLAY = Regex("^(please |can you |could you |hey ruby,? )?(play|put on|turn on|start|switch to|listen to|tune (in )?to)\\b")
    private val STOP = Regex(
        "^(please )?(stop|pause|turn off|switch off|shut off|kill|mute)( the)? (radio|music|station)( please)?$|^(radio|music) off$"
    )
    private val BARE_STOP = Regex("^(please )?(stop|pause|stop it|pause it|stop that|quiet|enough)( please)?$")

    /**
     * The radio command in [message], or null for an ordinary message. A bare "stop" only
     * counts while something is playing ([playing]: Ruby's radio, or another app such as
     * iHeartRadio); otherwise it's left to the chat.
     */
    fun parse(message: String, playing: Boolean): RadioCommand? {
        val m = message.lowercase().trim().trimEnd('.', '!', '?').trim()
        if (STOP.matches(m) || (playing && BARE_STOP.matches(m))) return RadioCommand.Stop
        if (PLAY.containsMatchIn(m)) {
            Stations.find(m)?.let { return RadioCommand.Play(it) }
            if (Regex("\\bradio\\b").containsMatchIn(m)) return RadioCommand.Play(Stations.DEFAULT)
        }
        return null
    }

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
