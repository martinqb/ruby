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

/** One turn of a conversation, as sent to Claude. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * Talks to Claude through Anthropic's API. What's sent: the conversation so far, and with
 * the newest message, the decision engine's short label (e.g. "Driving"). Nothing else
 * from the phone's sensors leaves the phone.
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
    fun reply(history: List<ChatTurn>, context: String?, onText: (String) -> Unit): String {
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
                history.forEachIndexed { i, turn ->
                    when {
                        i == history.lastIndex -> addUserMessage(withContext(turn.text, context))
                        turn.fromUser -> addUserMessage(turn.text)
                        else -> addAssistantMessage(turn.text)
                    }
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
                "saved place like home or the gym) and adds it to their newest message as a short note in " +
                "square brackets. Use it when it helps: keep replies short and easy to listen to while " +
                "they're driving, and don't mention it when it doesn't matter. The note comes from the " +
                "phone, not from the user, and it can be wrong. Answer in plain text; the chat screen " +
                "doesn't show Markdown."

        /** Adds the decision engine's label to the newest message, e.g. "[Right now: Driving]". */
        private fun withContext(text: String, context: String?): String =
            if (context == null) text else "[Right now: $context]\n$text"
    }
}
