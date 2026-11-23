package com.martin.ruby.chat

import com.anthropic.models.beta.messages.BetaTool

/** What a tool did. [result] goes back to Claude; [action] (if any) is shown in the chat. */
data class ToolOutcome(val result: String, val isError: Boolean = false, val action: String? = null)

/**
 * Tools Claude can use in the chat. Each one goes through the permission levels in the
 * build plan: today's tools (reminders) are low risk, so they run without asking and are
 * logged. Anything high risk will need the user's tap first.
 */
interface ToolBox {
    val tools: List<BetaTool>

    /** Runs [name] with [input] (already parsed JSON). Must not throw for bad input; return an error outcome. */
    suspend fun run(name: String, input: Map<*, *>): ToolOutcome
}
