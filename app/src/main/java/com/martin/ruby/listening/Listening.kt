package com.martin.ruby.listening

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.log.SensingLog
import com.martin.ruby.voice.VoiceSession
import com.martin.ruby.voice.VoiceUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model

/**
 * Ruby's listening modes, all in one place: conversation mode, the wake word and talking over
 * her. It owns the microphone for them and hands it to Android's speech recognizer whenever the
 * user is actually talking to her (the Talk button, after the wake word, in a conversation),
 * since Android gives the mic to one listener at a time. Main thread.
 */
object Listening {

    data class Ui(
        val prefs: ListeningPrefs = ListeningPrefs(),
        val model: VoskModel.State = VoskModel.State.NotInstalled,
        /** The model is loaded and the mic can be used for the modes. */
        val ready: Boolean = false,
        val mic: MicUse = MicUse.NONE,
        /** Why a mode can't run right now, or null. */
        val problem: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private var app: Context? = null
    private var model: Model? = null
    private var loadingModel = false
    private val modelLoaded = MutableStateFlow(false)
    private var spotter: VoskSpotter? = null
    private var startMic: Job? = null
    private var reopen: Job? = null

    /** The user's last turn was spoken (so conversation mode opens the mic after Ruby answers). */
    private var lastTurnSpoken = false

    private data class Now(val prefs: ListeningPrefs, val voice: VoiceUi, val answering: Boolean, val loaded: Boolean)
    private var now: Now? = null

    /** Starts following the settings and what Ruby is doing. Safe to call more than once. */
    fun start(context: Context) {
        if (app != null) return
        val a = context.applicationContext
        app = a
        VoiceSession.start(a)
        scope.launch { VoskModel.state(a).collect { s -> _ui.update { it.copy(model = s) } } }
        scope.launch {
            combine(
                ListeningSettings(a).prefs,
                VoiceSession.ui,
                ChatSession.ui.map { it.sending }.distinctUntilChanged(),
                modelLoaded,
            ) { prefs, voice, sending, loaded -> Now(prefs, voice, sending || voice.readingReply, loaded) }
                .collect { apply(a, it) }
        }
    }

    /** Ruby came on screen: background listening can start now (Android only allows that from the app). */
    fun appShown(context: Context) {
        start(context)
        scope.launch {
            val prefs = ListeningSettings(context).current()
            if (prefs.active && hasMic(context)) ListeningService.start(context)
        }
    }

    /** Saves [prefs] from the Voice settings page (Ruby is on screen, so the service may start). */
    fun save(context: Context, prefs: ListeningPrefs) {
        start(context)
        scope.launch {
            ListeningSettings(context).save(prefs)
            if (prefs.active && hasMic(context)) ListeningService.start(context)
        }
    }

    /** The quick on/off (main screen, notification). */
    fun setOn(context: Context, on: Boolean) {
        scope.launch {
            val settings = ListeningSettings(context)
            settings.update { it.copy(on = on) }
            if (on && hasMic(context)) ListeningService.start(context)
            SensingLog.add(if (on) "Listening: switched on." else "Listening: switched off (saves battery).")
        }
    }

    /** The model finished downloading. */
    fun modelInstalled() {
        scope.launch { app?.let { a -> now?.let { apply(a, it) } } }
    }

    /**
     * The user talks to Ruby: the Talk button, after the wake word, after talking over her, or
     * conversation mode opening the mic again. The modes give up the mic first.
     */
    fun talk(context: Context) {
        start(context)
        startMic?.cancel()
        spotter?.stop()
        _ui.update { it.copy(mic = MicUse.NONE) }
        VoiceSession.listen { heard -> heard(context.applicationContext, heard) }
    }

    private fun heard(context: Context, text: String) {
        val inConversation = now?.prefs?.let { it.active && it.conversation } == true
        if (inConversation && ConversationRules.endsConversation(text)) {
            lastTurnSpoken = false
            VoiceSession.showNote("Conversation ended. Tap Talk to start again.")
            return
        }
        scope.launch {
            // Talking over Ruby while her answer is still arriving: send once it's in.
            if (ChatSession.ui.value.sending) ChatSession.ui.first { !it.sending }
            lastTurnSpoken = ChatSession.send(context, text, spoken = true)
        }
    }

    private suspend fun apply(context: Context, n: Now) {
        now = n
        val prefs = n.prefs
        val mic = hasMic(context)

        // The listening model: loaded while a mode needs it, freed when none does.
        if (prefs.active && prefs.needsModel && VoskModel.isInstalled(context)) loadModel(context) else freeModel()
        val ready = mic && model != null
        _ui.update {
            it.copy(
                prefs = prefs,
                ready = ready,
                problem = when {
                    !prefs.active -> null
                    !mic -> "Ruby needs the microphone permission to listen."
                    prefs.needsModel && !VoskModel.isInstalled(context) -> "The listening model isn't downloaded yet."
                    else -> null
                },
            )
        }
        if (!prefs.active) ListeningService.stop(context)

        // The mic for the wake word and talking over her.
        val want = ListeningPlan.micUse(prefs, ready, speaking = n.voice.speaking, userListening = n.voice.listening)
        val s = spotter
        if (want == MicUse.NONE) {
            startMic?.cancel()
            s?.stop()
            _ui.update { it.copy(mic = MicUse.NONE) }
        } else if (s == null || s.use != want || s.phrase != prefs.wakePhrase) {
            startMic?.cancel()
            s?.stop()
            // A moment's pause, so Android's recognizer has let go of the mic.
            startMic = scope.launch {
                delay(MIC_HANDOFF_MS)
                val m = model ?: return@launch
                val sp = spotter ?: VoskSpotter(m).also { spotter = it }
                sp.start(want, prefs.wakePhrase, spotterListener)
                _ui.update { it.copy(mic = want) }
            }
        }

        // Conversation mode: open the mic again once Ruby is done.
        val again = ConversationRules.reopen(
            conversation = prefs.active && prefs.conversation && mic,
            lastTurnSpoken = lastTurnSpoken,
            answering = n.answering,
            speaking = n.voice.speaking,
            listening = n.voice.listening,
        )
        if (again && reopen?.isActive != true) {
            reopen = scope.launch {
                delay(REOPEN_AFTER_MS)
                val c = now ?: return@launch
                if (ConversationRules.reopen(c.prefs.active && c.prefs.conversation, lastTurnSpoken, c.answering, c.voice.speaking, c.voice.listening)) {
                    lastTurnSpoken = false // used up: if nothing is said, the conversation ends
                    talk(context)
                }
            }
        }
    }

    private val spotterListener = object : VoskSpotter.Listener {
        override fun onWake() {
            val a = app ?: return
            SensingLog.add("Listening: heard the wake word.")
            VoiceSession.stopSpeaking()
            // Screen off or locked: turn it on and show her (never unlocking the phone).
            WakeScreen.show(a)
            talk(a)
        }

        override fun onHeard(text: String) {
            val a = app ?: return
            if (!BargeIn.isUser(text, VoiceSession.recentlySaid())) return
            SensingLog.add("Listening: you talked over Ruby, so she stopped.")
            VoiceSession.stopSpeaking()
            talk(a)
        }

        override fun onProblem(message: String) {
            SensingLog.add("Listening: $message")
            _ui.update { it.copy(problem = message, mic = MicUse.NONE) }
        }
    }

    private suspend fun loadModel(context: Context) {
        if (model != null || loadingModel) return
        loadingModel = true
        try {
            model = withContext(Dispatchers.IO) {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                Model(VoskModel.dir(context).absolutePath)
            }
            modelLoaded.value = true
        } catch (e: Exception) {
            SensingLog.add("Listening model couldn't load: ${e.message}")
            _ui.update { it.copy(problem = "The listening model couldn't load. Try downloading it again.") }
        } finally {
            loadingModel = false
        }
    }

    private fun freeModel() {
        if (model == null) return
        startMic?.cancel()
        spotter?.stop()
        spotter = null
        model?.close()
        model = null
        modelLoaded.value = false
    }

    fun hasMic(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Which words of [phrase] the listening model can't hear (empty if it isn't downloaded yet). */
    suspend fun unknownWords(context: Context, phrase: String): List<String> {
        if (!VoskModel.isInstalled(context)) return emptyList()
        val known = VoskModel.knownWords(context, WakePhrase.words(phrase))
        return WakePhrase.unknownWords(phrase, known::contains)
    }

    private const val MIC_HANDOFF_MS = 400L
    private const val REOPEN_AFTER_MS = 600L
}
