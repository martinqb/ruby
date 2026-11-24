package com.martin.ruby.briefing

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.ai.AiSettings
import com.martin.ruby.ai.FreeMode
import com.martin.ruby.chat.ApiKeyStore
import com.martin.ruby.radio.Radio
import com.martin.ruby.radio.RadioCommands
import com.martin.ruby.radio.RadioSettings
import com.martin.ruby.radio.Stations
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.data.dataStore
import com.martin.ruby.log.SensingLog
import com.martin.ruby.memory.MemoryMessage
import com.martin.ruby.memory.MemoryStore
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.ZoneId

/** The briefing's settings, the user's profile and the last one spoken. */
data class BriefingState(
    val enabled: Boolean = false,
    val profile: BriefingProfile = BriefingProfile(),
    val lastSpokenDay: String? = null,
    val lastText: String? = null,
    val lastMillis: Long = 0,
    /** How the last one went, e.g. "Written by Claude in 4.2 s; 186 words, about 62 s." */
    val lastNote: String? = null,
    val recentGreetings: List<String> = emptyList(),
    val recentSignOffs: List<String> = emptyList(),
)

/**
 * Phase 8 step 1: the morning briefing. The first time the car connects each morning
 * (4 AM to noon), Ruby gathers what each of the user's chosen segments needs (see
 * [BriefingModule]), has Claude write it, and speaks it in her voice, under 2 minutes.
 * Without Claude (no key, no internet) she speaks a plain version made on the phone, so
 * there's always a briefing. Each one is saved to memory.
 *
 * What leaves the phone: the Home place's rough location to Open-Meteo, today's date to
 * Wikipedia, and the morning's facts to Anthropic to write it.
 */
object MorningBriefing {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = Mutex()

    /** Set by [stop] while one is being put together, so it isn't spoken once it's ready. */
    @Volatile private var stopped = false

    private val _working = MutableStateFlow(false)
    /** True while a briefing is being put together. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private object Keys {
        val ENABLED = booleanPreferencesKey("briefing_enabled")
        val PROFILE = stringPreferencesKey("briefing_profile")
        val LAST_DAY = stringPreferencesKey("briefing_last_day")
        val LAST_TEXT = stringPreferencesKey("briefing_last_text")
        val LAST_MILLIS = longPreferencesKey("briefing_last_millis")
        val LAST_NOTE = stringPreferencesKey("briefing_last_note")
        val RECENT_GREETINGS = stringPreferencesKey("briefing_recent_greetings")
        val RECENT_SIGN_OFFS = stringPreferencesKey("briefing_recent_sign_offs")
    }

    fun state(context: Context): Flow<BriefingState> = context.applicationContext.dataStore.data.map { p ->
        BriefingState(
            enabled = p[Keys.ENABLED] ?: false,
            profile = BriefingProfile.fromJson(p[Keys.PROFILE]),
            lastSpokenDay = p[Keys.LAST_DAY],
            lastText = p[Keys.LAST_TEXT],
            lastMillis = p[Keys.LAST_MILLIS] ?: 0,
            lastNote = p[Keys.LAST_NOTE],
            recentGreetings = strings(p[Keys.RECENT_GREETINGS]),
            recentSignOffs = strings(p[Keys.RECENT_SIGN_OFFS]),
        )
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        scope.launch { context.applicationContext.dataStore.edit { it[Keys.ENABLED] = enabled } }
        SensingLog.add("Briefing: turned ${if (enabled) "on" else "off"}.")
    }

    fun saveProfile(context: Context, profile: BriefingProfile) {
        scope.launch { context.applicationContext.dataStore.edit { it[Keys.PROFILE] = profile.toJson() } }
    }

    /** The car just connected: speak the briefing if it's on, it's morning, and it hasn't been spoken today. */
    fun carConnected(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val now = LocalDateTime.now()
            val state = state(app).first()
            if (!BriefingText.shouldSpeak(state.enabled, now, state.lastSpokenDay)) return@launch
            // Marked first, so the car connecting again meanwhile doesn't start a second one.
            app.dataStore.edit { it[Keys.LAST_DAY] = BriefingText.day(now) }
            SensingLog.add("Briefing: car connected; putting the morning briefing together.")
            run(app, foreground = false)
        }
    }

    /** Stop talking: stops her, and a briefing still being put together won't be spoken. */
    fun stop() {
        if (_working.value) stopped = true
        VoiceSession.stopSpeaking()
    }

    /** "Hear it now": any time, without counting as this morning's briefing. */
    fun speakNow(context: Context) {
        val app = context.applicationContext
        scope.launch { run(app, foreground = true) }
    }

    /** [foreground]: Ruby is open ("Hear it now"), so Free mode can be used; the car connecting isn't. */
    private suspend fun run(app: Context, foreground: Boolean) {
        if (!running.tryLock()) return // one at a time
        stopped = false
        _working.value = true
        try {
            val started = SystemClock.elapsedRealtime()
            val state = state(app).first()
            val online = Connection.isOnline(app)
            val facts = gather(app, state)
            val (briefing, how) = write(app, facts, foreground)
            val text = briefing.text
            val tookMs = SystemClock.elapsedRealtime() - started
            if (stopped) {
                SensingLog.add("Briefing: stopped before it was spoken.")
                return
            }
            val spokeAt = SystemClock.elapsedRealtime()
            VoiceSession.speakWhenReady(app, text, what = "Morning briefing")
            if (!foreground) scope.launch { radioAfter(app, spokeAt) }
            val note = BriefingText.note(how, tookMs, briefing.words, facts.notes, online)
            SensingLog.add("Briefing: $note")
            app.dataStore.edit {
                it[Keys.LAST_TEXT] = text
                it[Keys.LAST_MILLIS] = System.currentTimeMillis()
                it[Keys.LAST_NOTE] = note
                it[Keys.RECENT_GREETINGS] = json(BriefingText.remember(state.recentGreetings, briefing.part(BriefingModule.GREETING)))
                it[Keys.RECENT_SIGN_OFFS] = json(BriefingText.remember(state.recentSignOffs, briefing.part(BriefingModule.SIGN_OFF)))
            }
            runCatching { MemoryStore(app).add(MemoryMessage.ROLE_RUBY, "Morning briefing: $text") }
                .onFailure { SensingLog.add("Briefing: couldn't save it to memory (${it.message}).") }
        } catch (e: Exception) {
            SensingLog.add("Briefing failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            _working.value = false
            running.unlock()
        }
    }

    /** What the chosen segments need, gathered at the same time. Only what's needed is fetched. */
    internal suspend fun gather(app: Context, state: BriefingState): BriefingFacts = coroutineScope {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val profile = state.profile
        val today = now.toLocalDate()
        val needWeather = profile.has(BriefingModule.GLANCE) || profile.has(BriefingModule.HEADS_UP) ||
            (profile.has(BriefingModule.INTEREST) && profile.interest == Interest.SKY_TONIGHT)
        val home = PlaceStore(app).places.first().firstOrNull { it.name.equals("home", ignoreCase = true) }

        val forecast = async(Dispatchers.IO) {
            when {
                !needWeather -> null to null
                home == null -> null to "No weather: save a place called Home first."
                else -> try {
                    OpenMeteo.fetch(home.latitude, home.longitude) to null
                } catch (e: Exception) {
                    null to "No weather: ${e.message ?: "Open-Meteo couldn't be reached"}."
                }
            }
        }
        val events = async(Dispatchers.IO) {
            if (!profile.has(BriefingModule.GLANCE) && !profile.has(BriefingModule.HEADS_UP)) return@async null to null
            try {
                CalendarReader.eventsOn(app, today, zone)?.let { it to null }
                    ?: (null to "No calendar: Ruby isn't allowed to read it.")
            } catch (e: Exception) {
                null to "No calendar: ${e.message ?: e.javaClass.simpleName}."
            }
        }
        val reminders = async(Dispatchers.IO) {
            if (!profile.has(BriefingModule.GLANCE)) return@async emptyList()
            runCatching {
                ReminderStore(app).pending().mapNotNull { r ->
                    when {
                        r.place != null -> BriefingReminder(r.text, place = r.place)
                        r.dueMillis != null && BriefingText.isOn(r.dueMillis, today, zone) -> BriefingReminder(r.text, dueMillis = r.dueMillis)
                        else -> null
                    }
                }
            }.getOrDefault(emptyList())
        }
        val music = async(Dispatchers.IO) {
            if (!profile.has(BriefingModule.MUSIC)) return@async null to null
            try {
                MusicOnThisDay.candidates(MusicOnThisDay.fetch(today)).let { it to if (it.isEmpty()) "No music moments for today on Wikipedia." else null }
            } catch (e: Exception) {
                null to "No music on this day: ${e.message ?: "Wikipedia couldn't be reached"}."
            }
        }
        val (f, weatherNote) = forecast.await()
        val (e, calendarNote) = events.await()
        // Tonight's sky is worked out on the phone; the forecast adds the clouds.
        val (sky, skyNote) = when {
            !(profile.has(BriefingModule.INTEREST) && profile.interest == Interest.SKY_TONIGHT) -> null to null
            home == null -> null to "No sky: save a place called Home first."
            else -> try {
                SkyTonight.compute(home.latitude, home.longitude, today, zone, f) to null
            } catch (x: Exception) {
                null to "No sky: ${x.message ?: x.javaClass.simpleName}."
            }
        }
        val (m, musicNote) = music.await()
        BriefingFacts(
            now = now,
            profile = profile,
            forecast = f,
            weatherNote = weatherNote,
            events = if (profile.has(BriefingModule.GLANCE)) e else null,
            calendarNote = if (profile.has(BriefingModule.GLANCE)) calendarNote else null,
            reminders = reminders.await(),
            headsUp = if (profile.has(BriefingModule.HEADS_UP)) HeadsUp.notes(f, e, now, zone) else emptyList(),
            sky = sky,
            skyNote = skyNote,
            music = m,
            musicNote = musicNote,
            recentGreetings = state.recentGreetings,
            recentSignOffs = state.recentSignOffs,
        )
    }

    /** Claude's briefing, or the plain one if Claude can't be had; with how it was written. */
    private suspend fun write(app: Context, facts: BriefingFacts, foreground: Boolean): Pair<Briefing, String> {
        // Free mode: Gemini Nano writes it when Ruby is open; it refuses work in the background
        // (the car connecting), so then Claude writes it, and the note says why.
        var why: String? = null
        if (AiSettings(app).current() == AiEngine.FREE) {
            why = if (!foreground) {
                "Free mode can't run in the background"
            } else if (!FreeMode.ready()) {
                "Free mode isn't available right now"
            } else {
                try {
                    return freeBriefing(facts) to "Written by Free mode"
                } catch (e: FreeMode.Failed) {
                    "Free mode couldn't write it: ${e.message?.trimEnd('.')}"
                }
            }
            SensingLog.add("Briefing: $why.")
        }
        val because = why?.let { " ($it)" } ?: ""
        val key = ApiKeyStore(app).load() ?: return BriefingText.plain(facts) to "Plain briefing (${why?.let { "$it; " } ?: ""}no API key)"
        return try {
            BriefingWriter(key).write(facts) to "Written by Claude$because"
        } catch (e: BriefingWriter.Failed) {
            BriefingText.plain(facts) to "Plain briefing (${e.message?.trimEnd('.')})"
        }
    }

    /**
     * In the car, once she's finished: the radio, if that's on (off by default), and not if the
     * user stopped her partway.
     */
    private suspend fun radioAfter(app: Context, spokeAt: Long) {
        if (!RadioSettings(app).startsAfterBriefing()) return
        // Wait for her to start, then to finish (at most a few minutes).
        withTimeoutOrNull(30_000) { VoiceSession.ui.first { it.speaking } }
        withTimeoutOrNull(4 * 60_000L) { VoiceSession.ui.first { !it.speaking } } ?: return
        val stopped = VoiceSession.lastStoppedAt >= spokeAt
        if (!RadioCommands.startAfterBriefing(enabled = true, inCar = true, stoppedByUser = stopped)) {
            SensingLog.add("Briefing: not starting the radio (she was stopped).")
            return
        }
        withContext(Dispatchers.Main) { Radio.play(app, Stations.DEFAULT) }
    }

    /** The briefing written by Gemini Nano on the phone. Throws [FreeMode.Failed]. */
    private suspend fun freeBriefing(facts: BriefingFacts): Briefing {
        val answer = FreeMode.write(
            BriefingText.SYSTEM_PROMPT + BriefingText.FREE_MODE_RULES,
            BriefingText.prompt(facts, compact = true),
            maxTokens = 700,
        )
        return BriefingText.fit(BriefingText.parse(answer, facts.segments)).also {
            if (it.parts.isEmpty()) throw FreeMode.Failed("Free mode sent back an empty briefing.")
        }
    }

    private fun strings(json: String?): List<String> = try {
        val a = JSONArray(json ?: "[]")
        List(a.length()) { a.getString(it) }
    } catch (e: org.json.JSONException) {
        emptyList()
    }

    private fun json(list: List<String>) = JSONArray(list).toString()
}
