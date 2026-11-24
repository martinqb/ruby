package com.martin.ruby.briefing

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import com.martin.ruby.chat.ClaudeChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Has Claude write the morning briefing from the day's facts, with the user's own API key.
 * Only the facts in [BriefingText.prompt] are sent.
 */
class BriefingWriter(private val apiKey: String) {

    class Failed(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** The briefing, each segment written by Claude and kept under 2 minutes. Throws [Failed]. */
    suspend fun write(facts: BriefingFacts): Briefing = withContext(Dispatchers.IO) {
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val params = MessageCreateParams.builder()
                .model(ClaudeChat.MODEL)
                .maxTokens(1_500L)
                .system(BriefingText.SYSTEM_PROMPT)
                // A short, light piece of writing: low effort keeps it quick, so she speaks soon.
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacksDefault()
                .addUserMessage(BriefingText.prompt(facts))
                .build()
            val message = try {
                client.beta().messages().create(params)
            } catch (e: AnthropicServiceException) {
                ClaudeChat.logError("Briefing: Anthropic returned ${e.statusCode()}", e)
                throw Failed("Anthropic returned an error (${e.statusCode()}).", e)
            } catch (e: AnthropicIoException) {
                throw Failed("Couldn't reach Anthropic.", e)
            }
            if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw Failed("Claude declined to write it.")
            val answer = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
            val briefing = BriefingText.fit(BriefingText.parse(answer, facts.segments))
            if (briefing.parts.isEmpty()) throw Failed("Claude sent back an empty briefing.")
            briefing
        } finally {
            client.close()
        }
    }
}
