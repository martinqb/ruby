package com.martin.ruby.ai.models

import android.content.Context
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.chat.ClaudeChat
import com.martin.ruby.log.SensingLog

/**
 * The model behind the "On-phone model" and "Cloud or PC model" engines, ready to chat: which one,
 * what to call it, and whether it can use tools (checked once per model and remembered).
 */
object ModelEngine {

    data class Ready(val chat: OpenAiChat, val label: String, val toolsOn: Boolean)

    /** What's on screen for the active engine, e.g. "Qwen 3.5 9B (on phone)". Null for Claude and Free mode. */
    fun label(engine: AiEngine, choices: ModelChoices): String? = when (engine) {
        AiEngine.LOCAL -> choices.localModel?.let { "${it.name} (on phone)" } ?: "On-phone model (none chosen)"
        AiEngine.REMOTE -> choices.remoteEndpoint?.let { e -> (e.model.ifBlank { e.name }) + " (${e.name})" } ?: "Cloud or PC model (none chosen)"
        else -> null
    }

    /** Gets the chosen model ready (loading an on-phone one if needed). Throws [ClaudeChat.ChatError]. */
    suspend fun connect(context: Context, engine: AiEngine): Ready {
        val store = ModelStore(context)
        val choices = store.current()
        return when (engine) {
            AiEngine.LOCAL -> {
                val model = choices.localModel ?: throw ClaudeChat.ChatError("No on-phone model chosen. Pick one on the Models screen.")
                try {
                    LocalServer.ensure(context, model)
                } catch (e: IllegalStateException) {
                    throw ClaudeChat.ChatError(e.message ?: "The on-phone model couldn't start.")
                }
                val chat = OpenAiChat(LocalServer.baseUrl(), LocalServer.apiKey(), model.id, "${model.name} on the phone", local = true)
                Ready(chat, "${model.name} on the phone", toolsFor(store, ModelChoices.localKey(model), choices, chat))
            }
            AiEngine.REMOTE -> {
                val e = choices.remoteEndpoint ?: throw ClaudeChat.ChatError("No cloud or PC model chosen. Pick one on the Models screen.")
                ModelCatalog.baseUrlProblem(e.baseUrl)?.let { throw ClaudeChat.ChatError("${e.name}: $it") }
                if (e.model.isBlank()) throw ClaudeChat.ChatError("${e.name} needs a model name. Add it on the Models screen.")
                val key = store.loadKey(e.id)
                if (e.needsKey && key.isNullOrBlank()) throw ClaudeChat.ChatError("${e.name} needs an API key. Add it on the Models screen.")
                val chat = OpenAiChat(e.baseUrl, key, e.model, "${e.model} on ${e.name}")
                Ready(chat, "${e.model} on ${e.name}", toolsFor(store, ModelChoices.remoteKey(e), choices, chat))
            }
            else -> throw ClaudeChat.ChatError("That engine doesn't use a model from the Models screen.")
        }
    }

    private suspend fun toolsFor(store: ModelStore, key: String, choices: ModelChoices, chat: OpenAiChat): Boolean =
        choices.tools[key] ?: chat.canUseTools().also { works ->
            SensingLog.add("Models: $key " + if (works) "can use tools." else "can't use tools; actions are off with it.")
            store.setTools(key, works)
        }

    /**
     * Ruby's instructions for any other model: exactly Claude's, so her personality, memory and rules
     * are the same, plus what this model can't do.
     */
    fun system(toolsOn: Boolean): String = ClaudeChat.SYSTEM_PROMPT + "\n\n" + if (toolsOn) {
        "With this model, Ruby can use her reminder tools, but nothing that needs the user's yes on the phone: " +
            "those are turned down by the phone. Web search, skills and thinking are only available with Claude."
    } else {
        "With this model Ruby can't use her tools: she can't set, list or cancel reminders. If the user asks for one, " +
            "say plainly that actions are off with this model and suggest switching to Claude. Never say a reminder is set. " +
            "Web search, skills and thinking are only available with Claude."
    }

    /** Shown under her reply when this model can't use tools. */
    fun actionsOffNote(label: String) = "Actions are off: $label can't use tools, so Ruby can't set reminders with it."
}
