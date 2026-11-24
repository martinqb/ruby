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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    ) {
        val on: Boolean get() = playing || starting
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
            _state.value = _state.value.copy(playing = isPlaying, starting = if (isPlaying) false else _state.value.starting)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                _state.value = _state.value.copy(playing = false, starting = false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val name = _state.value.station?.name ?: "The radio"
            SensingLog.add("Radio: $name couldn't play (${error.errorCodeName}): ${error.message}")
            _state.value = _state.value.copy(
                playing = false, starting = false,
                problem = "$name couldn't play: " + if (error.errorCode in NETWORK_ERRORS) "no internet connection." else "the stream didn't work.",
            )
        }
    }

    /**
     * Plays [station]: inside Ruby if it has a public stream, else opens it in iHeartRadio.
     * Returns what Ruby says about it.
     */
    fun play(context: Context, station: Station): String {
        val stream = station.stream ?: return openInIHeart(context, station)
        playUri(context, Uri.parse(stream), station)
        return "Playing ${station.name}."
    }

    /** Plays [uri] as [station] (tests play a local file). */
    internal fun playUri(context: Context, uri: Uri, station: Station) {
        record("played ${station.name} in Ruby")
        _state.value = State(station = station, starting = true)
        scope.launch {
            try {
                val c = controller(context)
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
        controller?.run {
            stop()
            clearMediaItems()
        }
        _state.value = _state.value.copy(station = null, playing = false, starting = false, problem = null)
        record("stopped the radio in Ruby")
        return "Radio off."
    }

    /**
     * "Stop" said in the chat: stops Ruby's radio, or if another app is playing (like WEBN in
     * iHeartRadio), presses pause through Android's media controls and checks it worked. Returns
     * what Ruby says, true to what happened.
     */
    suspend fun stopSaid(context: Context): String {
        if (_state.value.on) return stop(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val app = _state.value.inIHeart?.takeIf { recentIHeart(_state.value) }
        if (!audio.isMusicActive) return if (app != null) "${app.name} isn't playing right now." else "The radio isn't on."
        pauseWhateverPlays(context)
        var stopped = false
        repeat(10) {
            kotlinx.coroutines.delay(150)
            if (!audio.isMusicActive) {
                stopped = true
                return@repeat
            }
        }
        record(if (stopped) "paused ${app?.name ?: "another app"}" else "pressed pause, but ${app?.name ?: "the other app"} kept playing")
        return RadioCommands.pauseReply(app, stopped)
    }

    /** Opens [station] in the iHeartRadio app (the fallback). Returns what Ruby says. */
    fun openInIHeart(context: Context, station: Station): String {
        val app = context.applicationContext
        val links = listOfNotNull(
            station.iHeartId?.let { Uri.parse("ihr://play/live/$it") },
            station.iHeartId?.let { Uri.parse("https://www.iheart.com/live/$it/") },
        )
        for (link in links) {
            try {
                app.startActivity(Intent(Intent.ACTION_VIEW, link).setPackage(IHEART_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                remember(station)
                return "Opening ${station.name} in iHeartRadio."
            } catch (e: ActivityNotFoundException) {
                // try the next link
            }
        }
        // No station link (or it failed): open the app itself.
        app.packageManager.getLaunchIntentForPackage(IHEART_PACKAGE)?.let {
            app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            remember(station)
            return "Opening iHeartRadio; look for ${station.name} there."
        }
        return "${station.name} plays in the iHeartRadio app, which isn't installed."
    }

    /** A station now plays in iHeartRadio: Ruby's own radio stops, so the two don't play at once. */
    private fun remember(station: Station) {
        if (_state.value.on) controller?.run {
            stop()
            clearMediaItems()
        }
        record("opened ${station.name} in the iHeartRadio app")
        _state.value = State(inIHeart = station, inIHeartSince = SystemClock.elapsedRealtime())
    }

    private fun pauseWhateverPlays(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
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

    /** For tests: the player's own volume (the listening dip; Ruby's voice ducks it through audio focus). */
    internal val volume: Float get() = controller?.volume ?: 1f

    private const val IHEART_RECENT_MS = 6 * 60 * 60_000L
    private const val LOG_KEPT = 5

    /** For tests: as if [station] had just been opened in iHeartRadio. */
    internal fun pretendOpenedInIHeart(station: Station?) {
        _state.value = if (station == null) State() else State(inIHeart = station, inIHeartSince = SystemClock.elapsedRealtime())
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
