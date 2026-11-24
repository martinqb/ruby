package com.martin.ruby.memory

import com.martin.ruby.ai.FreeMode

/**
 * Summarizes a day of chat with Gemini Nano (Free mode), with the same instructions as
 * [ChatSummarizer]. Nano takes much less text than Claude, so long days go in smaller parts.
 * Failures stop the run (it's picked up again next time), as with a bad key for Claude.
 */
class FreeSummarizer : Summaries.Summarize {

    override suspend fun summarize(day: String, messages: List<MemoryMessage>): String {
        val label = SummaryText.label(day)
        val parts = SummaryText.parts(SummaryText.lines(messages), PART_CHARS)
        if (parts.size == 1) return ask("Day: $label\n\n<messages>\n${parts[0]}\n</messages>")
        val partial = parts.mapIndexed { i, part ->
            ask("Day: $label (part ${i + 1} of ${parts.size})\n\n<messages>\n$part\n</messages>")
        }
        return ask(
            "Day: $label\n\nThese are summaries of consecutive parts of the same day. Combine them into one " +
                "summary of the day, following the same rules.\n\n" +
                partial.mapIndexed { i, s -> "<part n=\"${i + 1}\">\n$s\n</part>" }.joinToString("\n")
        )
    }

    private suspend fun ask(prompt: String): String = try {
        FreeMode.write(ChatSummarizer.SYSTEM_PROMPT, prompt, maxTokens = 500)
    } catch (e: FreeMode.Failed) {
        throw ChatSummarizer.Failed(e.message ?: "Free mode couldn't summarize.", stopRun = true, e)
    }

    companion object {
        /** About 7,500 tokens of messages per part, well inside Nano's 16,000. */
        const val PART_CHARS = 30_000
    }
}
