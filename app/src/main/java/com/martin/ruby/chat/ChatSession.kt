package com.martin.ruby.chat

import android.content.Context
import android.os.SystemClock
import com.martin.ruby.decision.ContextStatus
import com.martin.ruby.log.SensingLog
import com.martin.ruby.memory.MemoryMessage
import com.martin.ruby.memory.MemoryRecall
import com.martin.ruby.memory.MemoryStore
import com.martin.ruby.data.PlaceStore
import com.martin.ruby.reminders.ReminderStore
import com.martin.ruby.reminders.ReminderText
import com.martin.ruby.reminders.ReminderTools
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import com.martin.ruby.voice.VoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the chat screen shows. */
data class ChatUi(
    val turns: List<ChatTurn> = emptyList(),
    /** Claude's reply so far while it streams in, or null. */
    val replying: String? = null,
    val sending: Boolean = false,
    val error: String? = null,
    /** Null until checked. */
    val hasKey: Boolean? = null,
)

/**
 * The current conversation with Claude. It lives as long as the app, so leaving the chat
 * screen (or the screen rotating) doesn't lose it or cut off a reply. Every message and
 * reply is also saved to memory.
 *
 * A new conversation starts each time the app starts. Each message takes along a few
 * related messages from earlier conversations ([MemoryRecall]).
 */
object ChatSession {

    /** How many recent turns are sent with each message. */
    private const val MAX_TURNS_SENT = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    private val clientLock = Mutex()
    private var chat: ClaudeChat? = null

    fun checkKey(context: Context) {
        scope.launch {
            val has = ApiKeyStore(context).hasKey()
            _ui.update { it.copy(hasKey = has) }
        }
    }

    fun saveKey(context: Context, key: String) {
        scope.launch {
            ApiKeyStore(context).save(key)
            dropClient()
            _ui.update { it.copy(hasKey = true, error = null) }
        }
    }

    fun forgetKey(context: Context) {
        scope.launch {
            ApiKeyStore(context).clear()
            dropClient()
            _ui.update { it.copy(hasKey = false) }
        }
    }

    /** Memory ids of this conversation's messages, which recall leaves out (they're sent anyway). */
    private val conversationIds = mutableSetOf<Long>()

    /**
     * This conversation's tools, made once when it starts and sent unchanged with every
     * message. Claude's earlier thinking is only valid against the exact tools it was made
     * with; rebuilding them each message let them drift and broke the chat with a 400.
     */
    private var tools: ToolBox? = null

    /** Starts a fresh conversation. Memory keeps the old one, and can recall it from now on. */
    fun newConversation() {
        if (_ui.value.sending) return
        conversationIds.clear()
        tools = null
        _ui.update { it.copy(turns = emptyList(), error = null) }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Debug builds only: the next message is sent with a changed tool list (see [ClaudeChat.reply]). */
    @Volatile var breakNextMessage = false

    /**
     * Sends [text]; returns false (and does nothing) if a reply is still coming. [spoken]
     * means the user said it rather than typed it, so the reply may be read aloud.
     */
    fun send(context: Context, text: String, spoken: Boolean = false): Boolean {
        val message = text.trim()
        if (message.isEmpty() || _ui.value.sending) return false
        val appContext = context.applicationContext
        val label = currentContextLabel()
        val speak = VoiceSession.shouldSpeak(spoken)
        _ui.update {
            it.copy(turns = it.turns + ChatTurn(fromUser = true, text = message), sending = true, replying = "", error = null)
        }

        scope.launch {
            val memory = MemoryStore(appContext)
            // Look before saving the new message, so it doesn't find itself.
            val memories = recall(memory, message)
            saveToMemory(memory, MemoryMessage.ROLE_USER, message)
            val now = "${ReminderText.time(System.currentTimeMillis())} (${ZoneId.systemDefault().id})"
            val turn = ChatTurn(
                fromUser = true,
                text = message,
                // The reminders as they are right now, so Ruby never goes by stale tool results.
                sent = ClaudeChat.compose(
                    message, label, now, currentReminders(appContext), currentPlaces(appContext), MemoryRecall.formatRecalled(memories),
                ),
                memoriesUsed = memories.size,
            )
            _ui.update { it.copy(turns = it.turns.dropLast(1) + turn) }
            val history = _ui.value.turns
            VoiceSession.replyStarted(speak)
            try {
                val client = client(appContext)
                val toolBox = tools ?: ReminderTools(appContext).also { tools = it }
                val alter = breakNextMessage.also { breakNextMessage = false }
                val reply = client.reply(history.takeLast(MAX_TURNS_SENT).dropWhile { !it.fromUser }, toolBox, alter) { soFar ->
                    _ui.update { it.copy(replying = soFar) }
                    VoiceSession.replyProgress(soFar)
                }
                VoiceSession.replyFinished()
                _ui.update { state ->
                    // If earlier thinking had to be dropped to get this reply, drop it from the
                    // stored turns too, so the next message doesn't hit the same refusal.
                    val earlier = if (reply.thinkingStripped) {
                        state.turns.map { t -> t.transcript?.let { t.copy(transcript = it.withoutThinking()) } ?: t }
                    } else {
                        state.turns
                    }
                    state.copy(turns = earlier + ChatTurn(fromUser = false, text = reply.text, actions = reply.actions, transcript = reply.transcript))
                }
                saveToMemory(memory, MemoryMessage.ROLE_RUBY, reply.text)
            } catch (e: ClaudeChat.ChatError) {
                SensingLog.add("Chat: ${e.message}")
                VoiceSession.replyFailed(e.message ?: "I couldn't answer.")
                _ui.update { it.copy(error = e.message) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never show raw errors; log them whole.
                ClaudeChat.logError("Chat failed", e)
                VoiceSession.replyFailed(ClaudeChat.LOST_TRAIN_OF_THOUGHT)
                _ui.update { it.copy(error = ClaudeChat.LOST_TRAIN_OF_THOUGHT) }
            } finally {
                _ui.update { it.copy(sending = false, replying = null) }
            }
        }
        return true
    }

    /**
     * The same label memory uses: the strongest state of the last 10 seconds (so taking the
     * phone out to type doesn't turn "Walking" into "Moving"), or failing that the current one.
     */
    private fun currentContextLabel(): String? =
        (ContextStatus.strongestRecent(SystemClock.elapsedRealtime()) ?: ContextStatus.reading.value?.state)?.label

    /**
     * The waiting reminders, read fresh for this message. Null (no note) if memory can't be
     * opened; Ruby's instructions then fall back to calling list_reminders.
     */
    private suspend fun currentReminders(context: Context): String? = try {
        ReminderText.snapshot(ReminderStore(context).pending())
    } catch (e: Exception) {
        SensingLog.add("Couldn't read reminders for the chat: ${e.message}")
        null
    }

    /**
     * The saved places' names, read fresh for this message, e.g. "Saved places: Home, Work."
     * They go in the message rather than the tools, so the tools never change mid-chat.
     */
    private suspend fun currentPlaces(context: Context): String? = try {
        val names = PlaceStore(context).places.first().map { it.name }
        "Saved places: " + (names.joinToString(", ").ifEmpty { "none" }) + "."
    } catch (e: Exception) {
        null
    }

    /** Related past messages, or none if memory can't be searched (the chat still works). */
    private suspend fun recall(memory: MemoryStore, message: String): List<MemoryRecall.Recalled> = try {
        memory.recall(message, exclude = conversationIds.toSet()).also {
            if (it.isNotEmpty()) SensingLog.add("Chat: sending ${it.size} related memories along.")
        }
    } catch (e: Exception) {
        SensingLog.add("Couldn't search memory for the chat: ${e.message}")
        emptyList()
    }

    private suspend fun saveToMemory(memory: MemoryStore, role: String, text: String) {
        try {
            conversationIds += memory.add(role, text)
        } catch (e: Exception) {
            // The chat still works if memory can't be opened; the Memory screen explains why.
            SensingLog.add("Couldn't save a chat message to memory: ${e.message}")
        }
    }

    private suspend fun client(context: Context): ClaudeChat = clientLock.withLock {
        chat ?: run {
            val key = ApiKeyStore(context).load()
                ?: throw ClaudeChat.ChatError("No API key saved. Add one to start chatting.")
            ClaudeChat(key).also { chat = it }
        }
    }

    private suspend fun dropClient() = clientLock.withLock {
        chat?.let { old -> withContext(Dispatchers.IO) { old.close() } }
        chat = null
    }
}
