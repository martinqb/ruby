package com.martin.ruby.briefing

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The morning briefing's segments, in the order they're spoken. Each can be turned on or
 * off, so the briefing suits anyone. [words] is each one's share of the time: about 210
 * words in all, roughly 70 to 80 seconds in Ruby's voice, well under 2 minutes.
 */
enum class BriefingModule(val tag: String, val label: String, val explained: String, val words: Int) {
    GREETING("greeting", "Greeting", "A short, warm hello that changes every day.", 12),
    GLANCE("glance", "Today at a glance", "Weather for the drive and the day, today's calendar and reminders.", 70),
    INTEREST("interest", "Your interest", "Something you care about, like tonight's sky.", 55),
    MUSIC("music", "Music on this day", "One moment from music history on today's date, from Wikipedia.", 35),
    HEADS_UP("headsup", "Heads-up", "A quick practical note for the drive or the day, like rain later or slick roads.", 25),
    SIGN_OFF("signoff", "Sign-off", "A short, upbeat send-off that changes every day.", 12),
}

/** What the interest module covers. More will come (sports, news...). */
enum class Interest(val label: String, val explained: String) {
    SKY_TONIGHT(
        "Tonight's sky",
        "Moon phase, the chance of clear skies for a telescope, and planets worth catching, for your Home area.",
    ),
}

/**
 * Who the briefing is for: which segments are on, and the interest. Kept on the phone, so
 * each person sets their own; nothing about it is built into the app.
 */
data class BriefingProfile(
    val modules: Set<BriefingModule> = DEFAULT_MODULES,
    /** Null until one is chosen; the interest module says nothing without one. */
    val interest: Interest? = null,
) {
    fun has(module: BriefingModule) = module in modules &&
        (module != BriefingModule.INTEREST || interest != null)

    /** The segments that will be spoken, in order. */
    val spoken: List<BriefingModule> get() = BriefingModule.entries.filter(::has)

    fun with(module: BriefingModule, on: Boolean) = copy(modules = if (on) modules + module else modules - module)

    fun toJson(): String = JSONObject()
        .put("modules", JSONArray(modules.map { it.name }))
        .put("interest", interest?.name ?: JSONObject.NULL)
        .toString()

    companion object {
        /** A new profile: everything but the interest, which waits until one is chosen. */
        val DEFAULT_MODULES = setOf(
            BriefingModule.GREETING, BriefingModule.GLANCE, BriefingModule.MUSIC,
            BriefingModule.HEADS_UP, BriefingModule.SIGN_OFF,
        )

        /** Reads a saved profile; names this version doesn't know are skipped. */
        fun fromJson(json: String?): BriefingProfile {
            if (json.isNullOrBlank()) return BriefingProfile()
            return try {
                val o = JSONObject(json)
                val names = o.optJSONArray("modules") ?: JSONArray()
                val modules = (0 until names.length()).mapNotNull { i ->
                    BriefingModule.entries.firstOrNull { it.name == names.optString(i) }
                }.toSet()
                val interest = o.optString("interest").let { n -> Interest.entries.firstOrNull { it.name == n } }
                BriefingProfile(modules, interest)
            } catch (e: JSONException) {
                BriefingProfile()
            }
        }
    }
}
