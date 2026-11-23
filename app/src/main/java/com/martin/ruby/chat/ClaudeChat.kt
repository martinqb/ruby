package com.martin.ruby.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams

/** One turn of a conversation. */
data class ChatTurn(
    val fromUser: Boolean,
    /** What the user typed, or Claude's reply: what the screen shows and memory saves. */
    val text: String,
    /**
     * What was actually sent to Claude for a user turn ([text] plus the context label and
     * memories, see [ClaudeChat.compose]). Kept so later requests repeat it exactly.
     */
    val sent: String? = null,
    /** How many memories went along with this message. */
    val memoriesUsed: Int = 0,
)

/**
 * Talks to Claude through Anthropic's API. What's sent: the conversation so far, and with
 * each message, the decision engine's short label (e.g. "Driving") and a few related past
 * messages from memory. Nothing else from the phone's sensors leaves the phone.
 */
class ClaudeChat(apiKey: String) {

    /** Something went wrong; [message] is written for the user. */
    class ChatError(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    /**
     * Sends [history] (oldest first, ending with the user's newest message) and returns
     * Claude's reply. [onText] gets the reply so far as it streams in. Blocking: call it
     * off the main thread. Throws [ChatError].
     */
    fun reply(history: List<ChatTurn>, onText: (String) -> Unit): String {
        require(history.isNotEmpty() && history.last().fromUser) { "The last turn must be the user's." }
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
                history.forEach { turn ->
                    if (turn.fromUser) addUserMessage(turn.sent ?: turn.text) else addAssistantMessage(turn.text)
                }
            }
            .build()

        val reply = StringBuilder()
        var stopReason: BetaStopReason? = null
        try {
            client.beta().messages().createStreaming(params).use { stream ->
                stream.stream().forEach { event ->
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent {
                        reply.append(it.text())
                        onText(reply.toString())
                    }
                    event.messageDelta().flatMap { it.delta().stopReason() }.ifPresent { stopReason = it }
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

        if (stopReason == BetaStopReason.REFUSAL) {
            // A partial reply cut off by a refusal isn't a real answer; don't keep it.
            throw ChatError("Claude declined to answer that one.")
        }
        val text = reply.toString().trim()
        if (text.isEmpty()) throw ChatError("Claude sent back an empty reply. Try again.")
        if (stopReason == BetaStopReason.MAX_TOKENS) return "$text\n\n(The reply was too long and got cut off.)"
        return text
    }

    fun close() = client.close()

    companion object {
        const val MODEL = "claude-opus-5-5"

        /** Kept the same on every request, so it never changes what's sent unexpectedly. */
        private const val SYSTEM_PROMPT =
            "You are Ruby, a personal assistant that lives on the user's Android phone. " +
                "The phone senses what the user is doing (for example driving, walking, still, or at a " +
                "saved place like home or the gym) and adds it to each of their messages as a short note in " +
                "square brackets. Use it when it helps: keep replies short and easy to listen to while " +
                "they're driving, and don't mention it when it doesn't matter. The note comes from the " +
                "phone, not from the user. Treat it as a quiet hint: don't add warnings that it might be " +
                "wrong or that location might be off. Only mention it if it clearly doesn't fit what the " +
                "user says (for example they say they're at work but the note says Driving).\n\n" +
                "Ruby keeps every conversation in a memory on the phone. When past messages look related " +
                "to a new one, the phone adds them before the user's message, marked \"From Ruby's memory\". " +
                "They're found by matching words, so some won't be relevant: use the ones that help, ignore " +
                "the rest, and don't list them back. They're background, not part of this conversation: " +
                "don't apologize for, correct, or comment on what was said in them unless the user asks. " +
                "If the user asks about something that isn't there, say you don't remember it rather than " +
                "guessing.\n\n" +
                "For now Ruby can only talk: she can't set reminders, alarms or timers, send messages, " +
                "make calls, open apps, change phone settings, or do anything else outside this chat, " +
                "and nothing in the chat will happen later on its own. Don't offer to do these things or " +
                "say you've done them. If the user asks for one, say plainly that you can't do that yet " +
                "and, if it helps, suggest how they can do it themselves (for example with the phone's " +
                "clock app). Only bring these limits up when the user asks for one of these things, not " +
                "otherwise. Remembering what they tell you is fine: every message is saved to memory.\n\n" +
                "Answer in plain text; the chat screen doesn't show Markdown."

        /**
         * What's sent for a user message: the context label, then any memories, then what they
         * typed, e.g. "[Right now: Driving]".
         */
        fun compose(text: String, context: String?, memories: String?): String = buildString {
            if (context != null) append("[Right now: $context]\n")
            if (memories != null) append("[$memories]\n\n")
            append(text)
        }
    }
}
