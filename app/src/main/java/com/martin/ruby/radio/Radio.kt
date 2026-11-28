package com.martin.ruby.radio

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.martin.ruby.data.dataStore
import com.martin.ruby.log.SensingLog
import com.martin.ruby.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Ruby's radio, for the rest of the app: play a station, stop, open one in iHeartRadio, and
 * what's playing. The playing itself happens in [RadioService]. Main thread.
 */
object Radio {

    /** What the radio is doing. [station] is the one playing or starting; [problem] is why it couldn't. */
    data class State(
        val station: Station? = null,
        val playing: Boolean = false,
        val starting: Boolean = false,
        val problem: String? = null,
        /** A station opened in the iHeartRadio app, and when (it plays there, not in Ruby). */
        val inIHeart: Station? = null,
        val inIHeartSince: Long = 0,
        /** Whether [inIHeart] is playing there right now (audio is playing and it isn't Ruby's own radio). */
        val iHeartPlaying: Boolean = false,
    ) {
        val on: Boolean get() = playing || starting

        /** A station's button: "Open" only while that station plays in iHeartRadio, else "Play". */
        fun buttonLabel(station: Station): String =
            if (!station.playsInRuby && iHeartPlaying && inIHeart?.id == station.id) "Open" else "Play"
    }

    const val IHEART_PACKAGE = "com.clearchannel.iheartradio.controller"

    /** How far the radio goes down while Ruby listens to the user, so the mic hears them. */
    const val LISTENING_VOLUME = 0.15f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var controller: MediaController? = null

    /** What the phone did with the radio lately ("9:10 AM: played WGRR 103.5 in Ruby"), oldest first. */
    private val log = ArrayDeque<String>()

    private fun record(what: String) {
        val time = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US).format(java.time.LocalTime.now())
        log.addLast("$time: $what")
        while (log.size > LOG_KEPT) log.removeFirst()
        SensingLog.add("Radio: $what.")
    }

    /** The note for the AI: what's playing and what the phone did (see [RadioCommands.note]). */
    fun note(): String? {
        val s = _state.value
        val now = when {
            s.on -> "${s.station?.name} is playing in Ruby"
            s.inIHeart != null && recentIHeart(s) -> "${s.inIHeart.name} was opened in the iHeartRadio app (it plays there, not in Ruby)"
            else -> null
        }
        return RadioCommands.note(now, log.toList())
    }

    private fun recentIHeart(s: State) = s.inIHeart != null && SystemClock.elapsedRealtime() - s.inIHeartSince < IHEART_RECENT_MS

    /** Whether anything is playing: Ruby's radio, or another app such as iHeartRadio. */
    fun anythingPlaying(context: Context): Boolean =
        _state.value.on || context.getSystemService(AudioManager::class.java).isMusicActive

    private suspend fun controller(context: Context): MediaController = withContext(Dispatchers.Main) {
        controller?.takeIf { it.isConnected } ?: run {
            val app = context.applicationContext
            val token = SessionToken(app, ComponentName(app, RadioService::class.java))
            val future = MediaController.Builder(app, token).buildAsync()
            val c = suspendCancellableCoroutine { cont ->
                future.addListener({
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }, ContextCompat.getMainExecutor(app))
                cont.invokeOnCancellation { MediaController.releaseFuture(future) }
            }
            c.addListener(listener)
            controller = c
            watchListening()
            c
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = if (isPlaying) {
                _state.value.copy(playing = true, starting = false, problem = null) // (back) on air
            } else {
                _state.value.copy(playing = false)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller ?: return
            // The service clears the station when it stops or gives up reconnecting: then it's off.
            if ((playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) && c.mediaItemCount == 0) {
                val wasReconnecting = _state.value.problem?.contains("reconnecting") == true
                _state.value = _state.value.copy(
                    playing = false, starting = false,
                    problem = if (wasReconnecting) "${_state.value.station?.name ?: "The radio"} couldn't reconnect." else _state.value.problem,
                )
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val name = _state.value.station?.name ?: "The radio"
            SensingLog.add("Radio: $name (${error.errorCodeName}): ${error.message}")
            // The service tries again by itself (see Reconnect); meanwhile it still counts as on.
            val retrying = Reconnect.retriable(error.errorCode)
            _state.value = _state.value.copy(
                playing = false, starting = retrying,
                problem = when {
                    retrying && error.errorCode in NETWORK_ERRORS -> "$name dropped out (no connection); reconnecting…"
                    retrying -> "$name dropped out; reconnecting…"
                    else -> "$name couldn't play: the stream didn't work."
                },
            )
        }
    }

    /**
     * Plays [station]: inside Ruby if it has a public stream, else opens it in iHeartRadio.
     * Returns what Ruby says about it.
     */
    fun play(context: Context, station: Station): String {
        val stream = station.stream
            ?: return if (station.iHeartId != null) openInIHeart(context, station) else OtherRadioApps.open(context, station)
        playUri(context, Uri.parse(stream), station)
        return "Playing ${station.name}."
    }

    /** Plays [uri] as [station] (tests play a local file). */
    internal fun playUri(context: Context, uri: Uri, station: Station) {
        record("played ${station.name} in Ruby")
        stopsReportedFor = 0 // Ruby moved on from iHeartRadio herself: its stopping is no news
        _state.value = State(station = station, starting = true)
        val generation = ++playGeneration
        scope.launch {
            try {
                val c = controller(context)
                // Stopped (or another station chosen) while connecting: don't start it after all.
                if (generation != playGeneration) return@launch
                c.setMediaItem(
                    MediaItem.Builder()
                        .setUri(uri)
                        .setMediaId(station.id)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(station.name).setArtist("${station.city} · Ruby's radio").build())
                        .build()
                )
                c.prepare()
                c.play()
            } catch (e: Exception) {
                SensingLog.add("Radio: couldn't start (${e.message}).")
                _state.value = _state.value.copy(starting = false, problem = "The radio couldn't start.")
            }
        }
    }

    /** Stops Ruby's radio (the Stop buttons). Returns what Ruby says. */
    fun stop(context: Context): String {
        if (!_state.value.on) return "The radio isn't on."
        stopOwnPlayer()
        record("stopped the radio in Ruby")
        return "Radio off."
    }

    /** Tells Ruby's player to stop and forget the station (a start still on its way is dropped too). */
    private fun stopOwnPlayer() {
        playGeneration++
        controller?.run {
            stop()
            clearMediaItems()
        }
        _state.value = _state.value.copy(station = null, playing = false, starting = false, problem = null)
    }

    /** Whether Ruby's own player has really ended (the controller says so, not just what we asked). */
    private fun ownPlayerEnded(): Boolean {
        val c = controller ?: return true
        return !c.isPlaying && (c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED)
    }

    /**
     * "Stop" said in the chat (or by voice): stops whatever is playing. Ruby's own player first,
     * checked until it has really ended; then, if another app is still playing (like WEBN in
     * iHeartRadio), presses pause through Android's media controls and checks that too. Returns
     * what Ruby says, only claiming what the phone saw stop.
     */
    suspend fun stopSaid(context: Context): String = withContext(Dispatchers.Main) { stopEverything(context) }

    private suspend fun stopEverything(context: Context): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val app = _state.value.inIHeart?.takeIf { recentIHeart(_state.value) }
        val ours = _state.value.station?.takeIf { _state.value.on }
        if (ours == null && !audio.isMusicActive) {
            retryOfferedAt = 0
            return if (app != null) "${app.name} isn't playing right now." else "The radio isn't on."
        }

        // 1. Ruby's own player.
        var otherPlaying = true
        if (ours != null) {
            stopOwnPlayer()
            if (!waitFor(OWN_STOP_MS) { ownPlayerEnded() }) {
                record("pressed stop, but ${ours.name} kept playing in Ruby")
                return RadioCommands.stopReply(ours, ourStopped = false, otherPlaying = false, otherStopped = false, app = app)
            }
            record("stopped ${ours.name} in Ruby")
            // Android's "music playing" lags a moment behind Ruby's player: still on after that means another app.
            otherPlaying = !waitFor(SETTLE_MS) { !audio.isMusicActive }
        }

        // 2. Anything else still playing: pause, and if the app ignores that, stop.
        var otherStopped = false
        if (otherPlaying) {
            for (key in listOf(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) {
                pressMediaKey(context, key)
                otherStopped = waitFor(OTHER_STOP_MS) { !audio.isMusicActive }
                if (otherStopped) break
            }
            record(if (otherStopped) "paused ${app?.name ?: "another app"}" else "pressed pause, but ${app?.name ?: "the other app"} kept playing")
        }
        // Offer to try again only when something really is still playing.
        retryOfferedAt = if (otherPlaying && !otherStopped) SystemClock.elapsedRealtime() else 0
        return RadioCommands.stopReply(ours, ourStopped = true, otherPlaying = otherPlaying, otherStopped = otherStopped, app = app)
    }

    private var retryOfferedAt = 0L

    /** Whether Ruby just offered to try pausing again, so a "yes" means "stop" (good for two minutes). */
    fun retryOffered(): Boolean = retryOfferedAt != 0L && SystemClock.elapsedRealtime() - retryOfferedAt < RETRY_OFFER_MS

    /** The chat moved on to something else: a later "yes" is for the AI, not the radio. */
    fun forgetRetryOffer() {
        retryOfferedAt = 0
    }

    /** Checks [done] every 100 ms for up to [ms]; true as soon as it holds. */
    private suspend fun waitFor(ms: Long, done: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + ms
        while (true) {
            if (done()) return true
            if (SystemClock.elapsedRealtime() >= until) return false
            kotlinx.coroutines.delay(100)
        }
    }

    /** Opens [station] in the iHeartRadio app (the fallback). Returns what Ruby says. */
    fun openInIHeart(context: Context, station: Station): String {
        val app = context.applicationContext
        if (iHeartStandIn) {
            remember(app, station)
            return "Opening ${station.name} in iHeartRadio."
        }
        val links = listOfNotNull(
            station.iHeartId?.let { Uri.parse("ihr://play/live/$it") },
            station.iHeartId?.let { Uri.parse("https://www.iheart.com/live/$it/") },
        )
        for (link in links) {
            try {
                app.startActivity(Intent(Intent.ACTION_VIEW, link).setPackage(IHEART_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                remember(app, station)
                return "Opening ${station.name} in iHeartRadio."
            } catch (e: ActivityNotFoundException) {
                // try the next link
            }
        }
        // No station link (or it failed): open the app itself.
        app.packageManager.getLaunchIntentForPackage(IHEART_PACKAGE)?.let {
            app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            remember(app, station)
            return "Opening iHeartRadio; look for ${station.name} there."
        }
        return "${station.name} plays in the iHeartRadio app, which isn't installed."
    }

    /** A station now plays in iHeartRadio: Ruby's own radio stops, so the two don't play at once. */
    private fun remember(context: Context, station: Station) {
        if (_state.value.on) {
            playGeneration++
            controller?.run {
                stop()
                clearMediaItems()
            }
        }
        record("opened ${station.name} in the iHeartRadio app")
        _state.value = State(inIHeart = station, inIHeartSince = SystemClock.elapsedRealtime())
        val attempt = ++iHeartAttempt
        stopsReportedFor = 0
        watchOtherApps(context)
        scope.launch { checkIHeartStarted(context.applicationContext, station, attempt, checkTimes.first, checkTimes.second) }
    }

    /** For tests (the emulator has no iHeartRadio): "opening" a station there only starts the check. */
    internal var iHeartStandIn = false

    /** How long the check waits for iHeartRadio to play by itself, then after pressing play (tests shorten them). */
    internal var checkTimes = IHeartStartCheck.AUTOPLAY_MS to IHeartStartCheck.AFTER_PRESS_MS

    /** What happened after opening a station in iHeartRadio, for whoever asked (the chat, the briefing). */
    data class IHeartNews(val attempt: Int, val station: Station, val text: String, val failed: Boolean)

    private val _iHeartNews = MutableSharedFlow<IHeartNews>(extraBufferCapacity = 8)
    val iHeartNews: SharedFlow<IHeartNews> = _iHeartNews.asSharedFlow()

    /** Counts openings in iHeartRadio, so a check that's been overtaken by a newer one says nothing. */
    var iHeartAttempt = 0
        private set

    /** The opening whose later stop (iHeartRadio paused or closed) is worth telling about; 0 for none. */
    private var stopsReportedFor = 0

    /**
     * After opening [station] in iHeartRadio: did it really start? Waits for it, presses play if
     * it didn't, and tells [iHeartNews]. If it still isn't playing, the radio screen says so too.
     * Main thread.
     */
    internal suspend fun checkIHeartStarted(
        context: Context,
        station: Station,
        attempt: Int = iHeartAttempt,
        autoplayMs: Long = IHeartStartCheck.AUTOPLAY_MS,
        afterPressMs: Long = IHeartStartCheck.AFTER_PRESS_MS,
    ): IHeartStart = withContext(Dispatchers.Main) {
        val start = IHeartStartCheck.run(
            playing = { otherAppPlaying(context) },
            pressPlay = {
                if (!iHeartStandIn && IHeartRemote.pressPlay(context)) {
                    record("pressed play in iHeartRadio's player for ${station.name}")
                } else {
                    // A media key goes to whichever app played last: let go of Ruby's own radio
                    // first, so it reaches iHeartRadio rather than her.
                    releaseOwnSession(context)
                    kotlinx.coroutines.delay(RELEASE_MS)
                    record("pressed play (media key) for ${station.name} in iHeartRadio")
                    pressMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            },
            waitFor = { ms, done -> waitFor(ms, done) },
            autoplayMs = autoplayMs,
            afterPressMs = afterPressMs,
        )
        // Something newer happened meanwhile (another station, Ruby's own radio): say nothing.
        if (attempt != iHeartAttempt || _state.value.inIHeart?.id != station.id) return@withContext start
        val text = RadioCommands.iHeartStartReply(station, start)
        if (start == IHeartStart.NOT_PLAYING) {
            record("${station.name} opened in iHeartRadio but didn't start playing")
            // The radio screen shows why, with Open in iHeartRadio to try again.
            _state.value = _state.value.copy(station = station, problem = text, iHeartPlaying = false)
        } else {
            record("${station.name} started playing in iHeartRadio")
            stopsReportedFor = attempt
            refreshOtherApps(context)
        }
        _iHeartNews.tryEmit(IHeartNews(attempt, station, text, failed = start == IHeartStart.NOT_PLAYING))
        start
    }

    /**
     * Ends Ruby's radio service (her radio is off by now), so its media session is gone and stops
     * taking media keys meant for another app. The next "play" starts it again.
     */
    private fun releaseOwnSession(context: Context) {
        if (_state.value.on) return
        controller?.run {
            removeListener(listener)
            release()
        }
        controller = null
        context.stopService(Intent(context, RadioService::class.java))
    }

    /** Another app (not Ruby's radio, not Ruby talking) is playing audio. */
    private fun otherAppPlaying(context: Context): Boolean =
        !_state.value.on && !VoiceSession.ui.value.speaking &&
            context.getSystemService(AudioManager::class.java).isMusicActive

    private var watchingOtherApps = false

    /**
     * Follows other apps' playing (Android tells Ruby whenever any app starts or stops audio), so
     * a station's button goes back to "Play" once iHeartRadio stops or is closed.
     */
    private fun watchOtherApps(context: Context) {
        val app = context.applicationContext
        if (!watchingOtherApps) {
            watchingOtherApps = true
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            app.getSystemService(AudioManager::class.java).registerAudioPlaybackCallback(
                object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                        refreshOtherApps(app)
                        // Android's "music playing" can lag the change a moment: look again.
                        handler.postDelayed({ refreshOtherApps(app) }, 600)
                    }
                },
                handler,
            )
        }
        refreshOtherApps(app)
    }

    /** Looks again at whether the station opened in iHeartRadio is playing there (also when the radio screen comes back). */
    fun refreshOtherApps(context: Context) {
        val s = _state.value
        val playing = s.inIHeart != null && !s.on && context.getSystemService(AudioManager::class.java).isMusicActive
        if (playing == s.iHeartPlaying) return
        // Playing after all (pressed play there): the "didn't start" problem is over.
        _state.value = if (playing) s.copy(iHeartPlaying = true, problem = null, station = null) else s.copy(iHeartPlaying = false)
        val station = s.inIHeart
        if (!playing && station != null && stopsReportedFor == iHeartAttempt && stopsReportedFor != 0) {
            // It had started, and now it's quiet. Only tell about it if it stays that way and it was
            // iHeartRadio's own doing (paused there, or closed): not Ruby starting another station,
            // and not her mic or voice pausing it for a moment.
            val attempt = iHeartAttempt
            val app = context.applicationContext
            scope.launch {
                kotlinx.coroutines.delay(STOP_CONFIRM_MS)
                val now = _state.value
                if (!RadioCommands.reportIHeartStop(
                        sameOpening = attempt == iHeartAttempt && stopsReportedFor == attempt && now.inIHeart?.id == station.id,
                        stillQuiet = !now.iHeartPlaying && !otherAppPlaying(app),
                        rubysRadioOn = now.on,
                        rubyBusy = VoiceSession.ui.value.listening || VoiceSession.ui.value.speaking,
                    )
                ) return@launch
                stopsReportedFor = 0
                record("${station.name} stopped in iHeartRadio")
                _iHeartNews.tryEmit(IHeartNews(attempt, station, RadioCommands.iHeartStoppedReply(station), failed = false))
            }
        }
    }

    /** Whether the station opened in iHeartRadio is still the latest thing the radio did (chat checks before saying it stopped). */
    fun iHeartStillCurrent(attempt: Int, station: Station): Boolean =
        attempt == iHeartAttempt && !_state.value.on && _state.value.inIHeart?.id == station.id

    /** Presses a media key for whichever app is playing (Android sends it to the playing app's media session). */
    private fun pressMediaKey(context: Context, key: Int) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0))
    }

    private var watchingListening = false

    /** While Ruby listens to the user, the radio goes down so the mic hears them; back up after. */
    private fun watchListening() {
        if (watchingListening) return
        watchingListening = true
        scope.launch {
            VoiceSession.ui.map { it.listening }.distinctUntilChanged().collect { listening ->
                controller?.volume = if (listening) LISTENING_VOLUME else 1f
            }
        }
    }

    /** For tests: whether Ruby's own player is still playing (main thread). */
    internal fun ownPlayerPlaying(): Boolean = !ownPlayerEnded()

    /** For tests: the player's own volume (the listening dip; Ruby's voice ducks it through audio focus). */
    internal val volume: Float get() = controller?.volume ?: 1f

    /** Counts plays and stops, so a start that's still connecting knows it was stopped meanwhile. */
    private var playGeneration = 0

    private const val OWN_STOP_MS = 2_000L
    private const val RELEASE_MS = 800L
    /** How long iHeartRadio must stay quiet before Ruby says it stopped. */
    internal const val STOP_CONFIRM_MS = 3_000L
    private const val SETTLE_MS = 1_000L
    private const val OTHER_STOP_MS = 1_500L
    private const val RETRY_OFFER_MS = 2 * 60_000L
    private const val IHEART_RECENT_MS = 6 * 60 * 60_000L
    private const val LOG_KEPT = 5

    /** For tests: as if [station] had just been opened in iHeartRadio. */
    internal fun pretendOpenedInIHeart(station: Station?, context: Context? = null) {
        _state.value = if (station == null) State() else State(inIHeart = station, inIHeartSince = SystemClock.elapsedRealtime())
        if (station != null && context != null) watchOtherApps(context)
    }

    private val NETWORK_ERRORS = setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    )
}

/** The radio's settings: start it after the car's morning briefing (off by default). */
class RadioSettings(private val context: Context) {

    private val afterBriefing = booleanPreferencesKey("radio_after_briefing")

    val startAfterBriefing: Flow<Boolean> = context.applicationContext.dataStore.data.map { it[afterBriefing] ?: false }

    suspend fun startsAfterBriefing(): Boolean = startAfterBriefing.first()

    suspend fun setStartAfterBriefing(on: Boolean) {
        context.applicationContext.dataStore.edit { it[afterBriefing] = on }
    }
}
