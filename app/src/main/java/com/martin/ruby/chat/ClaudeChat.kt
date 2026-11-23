package com.martin.ruby.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaContentBlock
import com.anthropic.models.beta.messages.BetaContentBlockParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaToolChoiceNone
import com.anthropic.models.beta.messages.BetaToolResultBlockParam
import com.anthropic.models.beta.messages.BetaToolUseBlock
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One turn of a conversation. */
data class ChatTurn(
    val fromUser: Boolean,
    /** What the user typed, or Claude's reply: what the screen shows and memory saves. */
    val text: String,
    /**
     * What was actually sent to Claude for a user turn ([text] plus the context label,
     * time and memories, see [ClaudeChat.compose]). Kept so later requests repeat it exactly.
     */
    val sent: String? = null,
    /** How many memories went along with this message. */
    val memoriesUsed: Int = 0,
    /** What Ruby did with tools during this reply, e.g. "Reminder set: Call Mom, ...". */
    val actions: List<String> = emptyList(),
    /**
     * For Ruby's turns: everything Claude sent and got back in this reply, tool calls and
     * results included. Later requests send this instead of just [text], so Claude sees its
     * own earlier actions (without it, it saw "reminder set" with nothing behind it and set
     * the reminder again).
     */
    val transcript: ReplyTranscript? = null,
)

/** One reply's messages as they went to and from the API, to send again unchanged. */
class ReplyTranscript internal constructor(
    internal val rounds: List<Pair<BetaMessage, List<BetaContentBlockParam>>>,
    /** Claude's last message in the reply, or null if it had nothing to send again. */
    internal val final: BetaMessage?,
)

/**
 * Talks to Claude through Anthropic's API. What's sent: the conversation so far, and with
 * each message, the decision engine's short label (e.g. "Driving"), the time, and a few
 * related past messages from memory. Nothing else from the phone's sensors leaves the phone.
 */
class ClaudeChat(apiKey: String) {

    /** Something went wrong; [message] is written for the user. */
    class ChatError(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Claude's answer, what it did with tools on the way, and the whole exchange for later turns. */
    data class Reply(val text: String, val actions: List<String>, val transcript: ReplyTranscript)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /**
     * Sends [history] (oldest first, ending with the user's newest message) and returns
     * Claude's reply. When Claude calls one of [toolBox]'s tools, it's run and the result
     * sent back, until Claude answers. [onText] gets the visible reply so far as it streams
     * in (from any thread). Throws [ChatError].
     */
    suspend fun reply(history: List<ChatTurn>, toolBox: ToolBox?, onText: (String) -> Unit): Reply {
        require(history.isNotEmpty() && history.last().fromUser) { "The last turn must be the user's." }

        // This reply's tool rounds so far: Claude's message, then the results sent back.
        val rounds = mutableListOf<Pair<BetaMessage, List<BetaContentBlockParam>>>()
        val shown = mutableListOf<String>() // text from earlier rounds
        val actions = mutableListOf<String>()

        for (round in 0..MAX_TOOL_ROUNDS) {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16_000L)
                .system(SYSTEM_PROMPT)
                // Medium effort suits everyday chat; it's also this model's default, set explicitly.
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
                // If Anthropic's safety checks decline a message, let the API retry it on a
                // suitable fallback model instead of just stopping.
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacksDefault()
                .apply {
                    // Tools are always listed (earlier turns' tool calls need them), but the last
                    // round doesn't allow calling them, so Claude has to answer.
                    toolBox?.tools?.forEach { addTool(it) }
                    if (toolBox != null && round == MAX_TOOL_ROUNDS) toolChoice(BetaToolChoiceNone.builder().build())
                    history.forEach { turn ->
                        val transcript = turn.transcript
                        when {
                            turn.fromUser -> addUserMessage(turn.sent ?: turn.text)
                            transcript != null && toolBox != null -> {
                                transcript.rounds.forEach { (message, results) ->
                                    addMessage(message)
                                    addMessage(BetaMessageParam.ofUserOfContentBlockParams(results))
                                }
                                transcript.final?.let { addMessage(it) }
                            }
                            else -> addAssistantMessage(turn.text)
                        }
                    }
                    rounds.forEach { (message, results) ->
                        addMessage(message)
                        addMessage(BetaMessageParam.ofUserOfContentBlockParams(results))
                    }
                }
                .build()

            val message = stream(params) { current -> onText(join(shown + current)) }
            val stopReason = message.stopReason().orElse(null)
            if (stopReason == BetaStopReason.REFUSAL) {
                // A partial reply cut off by a refusal isn't a real answer, and a tool call in it
                // may be cut off too: run nothing and keep nothing.
                throw ChatError("Claude declined to answer that one.")
            }
            val usable = afterLastFallback(message)
            val text = usable.mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            val toolUses = usable.mapNotNull { it.toolUse().orElse(null) }

            if (stopReason == BetaStopReason.TOOL_USE && toolUses.isNotEmpty() && toolBox != null) {
                if (text.isNotEmpty()) shown += text
                // All results go back together in one message.
                val results = toolUses.map { use -> runTool(toolBox, use, actions) }
                rounds += message.toBuilder().content(echoable(message)).build() to results
                continue
            }

            // Sent again in later turns. Only if it has text: a message of nothing but thinking
            // (or a cut-off tool call) isn't worth replaying.
            val final = message.toBuilder().content(echoable(message).filterNot { it.isToolUse() }).build()
                .takeIf { m -> m.content().any { it.text().isPresent } }
            val transcript = ReplyTranscript(rounds.toList(), final)
            val all = join(if (text.isEmpty()) shown else shown + text)
            if (all.isEmpty()) {
                if (actions.isNotEmpty()) return Reply(actions.joinToString("\n"), actions, transcript)
                throw ChatError("Claude sent back an empty reply. Try again.")
            }
            // A tool call cut off by the length limit is never run; it just ends the reply.
            if (stopReason == BetaStopReason.MAX_TOKENS) {
                return Reply("$all\n\n(The reply was too long and got cut off.)", actions, transcript)
            }
            return Reply(all, actions, transcript)
        }
        throw ChatError("Ruby got stuck using her tools. Try asking again.")
    }

    /** Streams one request, passing the visible text so far to [onText], and returns the whole message. */
    private suspend fun stream(params: MessageCreateParams, onText: (String) -> Unit): BetaMessage =
        withContext(Dispatchers.IO) {
            val accumulator = BetaMessageAccumulator.create()
            val text = StringBuilder()
            try {
                client.beta().messages().createStreaming(params).use { stream ->
                    stream.stream().forEach { event ->
                        accumulator.accumulate(event)
                        event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent {
                            text.append(it.text())
                            onText(text.toString().trim())
                        }
                    }
                }
            } catch (e: UnauthorizedException) {
                throw ChatError("Anthropic didn't accept the API key. Check it with \"Change key\".", e)
            } catch (e: PermissionDeniedException) {
                throw ChatError("This API key isn't allowed to use $MODEL.", e)
            } catch (e: RateLimitException) {
                throw ChatError("Too many messages at once. Wait a minute and try again.", e)
            } catch (e: InternalServerException) {
                throw ChatError("Anthropic's servers had a problem. Try again in a moment.", e)
            } catch (e: AnthropicServiceException) {
                throw ChatError("Anthropic returned an error (${e.statusCode()}): ${e.message}", e)
            } catch (e: AnthropicIoException) {
                throw ChatError("Couldn't reach Anthropic. Check the internet connection.", e)
            }
            accumulator.message()
        }

    /**
     * Runs one tool call. Its input streamed in unchecked (eager input streaming), so input
     * that isn't a JSON object is answered with an error instead of run; the tool itself
     * checks each field.
     */
    private suspend fun runTool(toolBox: ToolBox, use: BetaToolUseBlock, actions: MutableList<String>): BetaContentBlockParam {
        val input: Map<*, *>? = try {
            use._input().convert(Map::class.java)
        } catch (e: Exception) {
            null
        }
        val outcome = if (input == null) {
            ToolOutcome("{\"INVALID_JSON\": ${quote(use._input().toString())}}", isError = true)
        } else {
            toolBox.run(use.name(), input)
        }
        outcome.action?.let { actions += it }
        return BetaContentBlockParam.ofToolResult(
            BetaToolResultBlockParam.builder()
                .toolUseId(use.id())
                .content(outcome.result)
                .isError(outcome.isError)
                .build()
        )
    }

    fun close() = client.close()

    companion object {
        const val MODEL = "claude-opus-5-5"

        /** How many times one reply may call tools before Claude must answer. */
        private const val MAX_TOOL_ROUNDS = 5

        /** Kept the same on every request, so it never changes what's sent unexpectedly. */
        private const val SYSTEM_PROMPT =
            "You are Ruby, a personal assistant that lives on the user's Android phone. " +
                "The phone senses what the user is doing (for example driving, walking, still, or at a " +
                "saved place like home or the gym) and adds it, with the current time, to each of their " +
                "messages as a short note in square brackets. Use it when it helps: keep replies short and " +
                "easy to listen to while they're driving, and don't mention it when it doesn't matter. The " +
                "note comes from the phone, not from the user. Treat it as a quiet hint: don't add warnings " +
                "that it might be wrong or that location might be off. Only mention it if it clearly doesn't " +
                "fit what the user says (for example they say they're at work but the note says Driving).\n\n" +
                "Ruby keeps every conversation in a memory on the phone. When past messages look related " +
                "to a new one, the phone adds them before the user's message, marked \"From Ruby's memory\". " +
                "They're found by matching words, so some won't be relevant: use the ones that help, ignore " +
                "the rest, and don't list them back. They're background, not part of this conversation: " +
                "don't apologize for, correct, or comment on what was said in them unless the user asks. " +
                "If the user asks about something that isn't there, say you don't remember it rather than " +
                "guessing.\n\n" +
                "Ruby can set reminders, for a time or for the next time the user arrives at one of their " +
                "saved places, and list or cancel them, with the reminder tools. Use them when the user asks " +
                "to be reminded of something. Work out times from the current time in their message; if a " +
                "time is unclear (\"later\", \"tonight\"), pick a sensible one and say which, or ask if it " +
                "matters. After setting one, confirm it briefly in plain words. Only say a reminder is set " +
                "once the tool says so. Tool calls and results earlier in this conversation really happened: " +
                "don't set a reminder again because you're unsure whether it went through; check with " +
                "list_reminders instead. Reminders don't repeat yet.\n\n" +
                "Apart from reminders, Ruby can only talk for now: she can't set alarms or timers, send " +
                "messages, make calls, open apps, change phone settings, or do anything else outside this " +
                "chat. Don't offer to do those things or say you've done them. If the user asks for one, say " +
                "plainly that you can't do that yet and, if it helps, suggest how they can do it themselves. " +
                "Only bring these limits up when the user asks for one of these things, not otherwise. " +
                "Remembering what they tell you is fine: every message is saved to memory.\n\n" +
                "Answer in plain text; the chat screen doesn't show Markdown."

        /**
         * What's sent for a user message: the context label and time, then any memories,
         * then what they typed, e.g. "[Right now: Driving · Mon, Nov 23, 2026, 3:42 PM (America/New_York)]".
         */
        fun compose(text: String, context: String?, now: String, memories: String?): String = buildString {
            append("[Right now: ")
            if (context != null) append("$context · ")
            append("$now]\n")
            if (memories != null) append("[$memories]\n\n")
            append(text)
        }

        private fun join(parts: List<String>): String = parts.filter { it.isNotEmpty() }.joinToString("\n\n")

        /**
         * After a server-side fallback partway through a reply, only what comes after the last
         * fallback block counts: tool calls before it belong to the declined attempt.
         */
        private fun afterLastFallback(message: BetaMessage): List<BetaContentBlock> {
            val content = message.content()
            val last = content.indexOfLast { it.isFallback() }
            return if (last < 0) content else content.subList(last + 1, content.size)
        }

        /**
         * The message as it's sent back with tool results. Before the last fallback block,
         * thinking and tool calls are left out (the API asks for that); the block itself stays
         * where it was.
         */
        private fun echoable(message: BetaMessage): List<BetaContentBlock> {
            val content = message.content()
            val last = content.indexOfLast { it.isFallback() }
            if (last < 0) return content
            return content.filterIndexed { i, block ->
                i >= last || !(block.isThinking() || block.isRedactedThinking() || block.isToolUse())
            }
        }

        private fun quote(text: String): String =
            "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
