package com.martin.ruby.voice

import android.content.Context
import com.martin.ruby.decision.ContextState
import com.martin.ruby.decision.ContextStatus
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the screen shows about Ruby's voice and ears. */
data class VoiceUi(
    val prefs: VoicePrefs = VoicePrefs(),
    val speaking: Boolean = false,
    val listening: Boolean = false,
    /** The words heard so far while listening. */
    val heard: String = "",
    /** A short note after listening, e.g. "Didn't catch that", or null. */
    val note: String? = null,
    /** Why Ruby can't speak, or null. */
    val problem: String? = null,
    /**
     * The voice actually speaking: Android text-to-speech stands in for Kokoro until
     * Kokoro is downloaded. Null before the settings are read.
     */
    val usingEngine: VoiceEngine? = null,
    val canListen: Boolean = true,
    /** True if speech recognition happens on the phone. */
    val listensOnDevice: Boolean = true,
)

/**
 * Ruby's voice and ears for the whole app: which [RubyVoice] is in use, when replies are
 * read aloud ([SpeakMode]), and listening with [RubyEars]. Everything here runs on the
 * main thread.
 */
object VoiceSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    private var appContext: Context? = null
    private var voice: RubyVoice? = null
    /** What the current [voice] was made for: its engine and Kokoro speaker. */
    private var voiceKey: Pair<VoiceEngine, KokoroSpeaker>? = null
    private var lastPrefs: VoicePrefs? = null
    private var voiceWatch: Job? = null
    private var ears: RubyEars? = null

    /** Splits the reply being spoken into sentences; null when this reply isn't spoken. */
    private var chunker: SpeechChunker? = null

    /** Starts watching the voice settings. Safe to call more than once. */
    fun start(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        ears = AndroidSpeechEars(app).also { e ->
            _ui.update { it.copy(canListen = e.available, listensOnDevice = e.onDevice) }
        }
        scope.launch {
            VoiceSettings(app).prefs.collect { prefs ->
                _ui.update { it.copy(prefs = prefs) }
                useVoice(app, prefs)
            }
        }
    }

    fun save(context: Context, prefs: VoicePrefs) {
        scope.launch { VoiceSettings(context.applicationContext).save(prefs) }
    }

    /** Whether to read the coming reply aloud. [spoken] means the user talked rather than typed. */
    fun shouldSpeak(spoken: Boolean): Boolean = when (_ui.value.prefs.mode) {
        SpeakMode.OFF -> false
        SpeakMode.ALWAYS -> true
        SpeakMode.AUTO -> spoken || inVehicle()
    }

    /** A reply is starting; read it aloud as it arrives if [speak]. */
    fun replyStarted(speak: Boolean) = onMain {
        voice?.stop()
        chunker = if (speak) SpeechChunker() else null
    }

    /** More of the reply has arrived. Can be called from any thread. */
    fun replyProgress(soFar: String) = onMain {
        chunker?.feed(soFar)?.forEach { voice?.say(it) }
    }

    fun replyFinished() = onMain {
        chunker?.finish()?.forEach { voice?.say(it) }
        chunker = null
    }

    /** The reply failed; if it was being spoken, say why instead. */
    fun replyFailed(message: String) = onMain {
        if (chunker != null) {
            voice?.stop()
            voice?.say("Sorry. $message")
        }
        chunker = null
    }

    /** Reads [text] aloud now, e.g. the Play button on a reply. */
    fun play(text: String) {
        chunker = null
        voice?.stop()
        val c = SpeechChunker()
        (c.feed(text) + c.finish()).forEach { voice?.say(it) }
    }

    fun stopSpeaking() {
        chunker = null
        voice?.stop()
    }

    /** Listens once; [onHeard] gets what the user said. Needs the microphone permission. */
    fun listen(onHeard: (String) -> Unit) {
        val e = ears ?: return
        stopSpeaking() // so she doesn't hear herself
        _ui.update { it.copy(listening = true, heard = "", note = null, listensOnDevice = e.onDevice) }
        e.listen(object : RubyEars.Listener {
            override fun onPartial(text: String) = _ui.update { it.copy(heard = text) }

            override fun onResult(text: String) {
                _ui.update { it.copy(listening = false, heard = "", listensOnDevice = e.onDevice) }
                onHeard(text)
            }

            override fun onError(message: String) =
                _ui.update { it.copy(listening = false, heard = "", note = message, listensOnDevice = e.onDevice) }
        })
    }

    /** Stops listening and uses what was heard so far. */
    fun finishListening() {
        ears?.stop()
    }

    fun cancelListening() {
        ears?.cancel()
        _ui.update { it.copy(listening = false, heard = "") }
    }

    fun dismissNote() = _ui.update { it.copy(note = null) }

    /** The Kokoro model finished downloading: switch to it if it's the chosen voice. */
    fun kokoroInstalled(context: Context) = onMain {
        lastPrefs?.let { useVoice(context.applicationContext, it) }
    }

    private fun useVoice(context: Context, prefs: VoicePrefs) {
        lastPrefs = prefs
        // Kokoro needs its model; until it's downloaded, Android text-to-speech speaks.
        val engine = if (prefs.engine == VoiceEngine.KOKORO && !KokoroModel.isInstalled(context)) VoiceEngine.ANDROID else prefs.engine
        val key = engine to prefs.kokoroSpeaker
        if (voiceKey != key || voice == null) {
            voice?.close()
            voiceWatch?.cancel()
            val v = engine.create(context, prefs)
            voice = v
            voiceKey = key
            _ui.update { it.copy(usingEngine = engine) }
            SensingLog.add(
                "Voice: using ${engine.label}" + if (engine == VoiceEngine.KOKORO) " (${prefs.kokoroSpeaker.label})." else "."
            )
            voiceWatch = scope.launch {
                launch { v.speaking.collect { s -> _ui.update { it.copy(speaking = s) } } }
                launch { v.problem.collect { p -> _ui.update { it.copy(problem = p) } } }
            }
        }
        voice?.setStyle(prefs.style)
    }

    private fun inVehicle(): Boolean = ContextStatus.reading.value?.state?.kind in
        setOf(ContextState.Kind.DRIVING, ContextState.Kind.IN_VEHICLE)

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }
}
