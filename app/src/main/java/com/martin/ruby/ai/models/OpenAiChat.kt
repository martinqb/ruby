package com.martin.ruby.ai.models

import com.martin.ruby.chat.ChatTurn
import com.martin.ruby.chat.ClaudeChat
import com.martin.ruby.chat.ToolBox
import com.martin.ruby.log.SensingLog
import com.martin.ruby.reminders.ReminderTools
import com.martin.ruby.reminders.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Chat with any model that speaks the OpenAI-compatible API: the on-phone llama.cpp server,
 * Ollama or LM Studio on the PC, or OpenRouter. Ruby's instructions, memory notes and tools are
 * the same as with Claude; the phone runs the tools, and only those [ModelSafety] allows.
 */
class OpenAiChat(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    /** What the user calls it, for messages ("Qwen 3.5 9B on the phone"). */
    private val label: String,
    /** The on-phone server: turn the model's own thinking off (thinking is Claude-only, and slow on a phone). */
    private val local: Boolean = false,
    private val readTimeoutMs: Int = if (local) 600_000 else 180_000,
) {

    data class Reply(val text: String, val actions: List<String>)

    /**
     * Sends [history] (ending with the user's newest message, already composed with Ruby's notes)
     * and returns the reply. With [toolBox], the model may call the tools [ModelSafety] allows;
     * the phone runs them and sends the results back, a few rounds at most. Throws [ClaudeChat.ChatError].
     */
    suspend fun reply(system: String, history: List<ChatTurn>, toolBox: ToolBox?, onText: (String) -> Unit): Reply =
        withContext(Dispatchers.IO) {
            val messages = messagesFor(system, history)
            val specs = toolBox?.let { ModelSafety.offered(ReminderTools.SPECS.map { s -> s.name }) }
                ?.let { allowed -> ReminderTools.SPECS.filter { it.name in allowed } }.orEmpty()
            val actions = mutableListOf<String>()
            val shown = mutableListOf<String>()
            var round = 0
            while (true) {
                val lastRound = round >= MAX_TOOL_ROUNDS
                val body = request(model, messages, if (lastRound) emptyList() else specs, stream = true, noThinking = local)
                val parsed = post(body) { soFar -> onText(join(shown + soFar)) }
                if (parsed.toolCalls.isNotEmpty() && toolBox != null && !lastRound) {
                    if (parsed.text.isNotBlank()) shown += parsed.text.trim()
                    messages.put(assistantWithTools(parsed))
                    for (call in parsed.toolCalls) {
                        val result = runTool(toolBox, call, actions)
                        messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.id).put("content", result))
                    }
                    round++
                    continue
                }
                val all = join(shown + parsed.text.trim())
                if (all.isEmpty()) {
                    if (actions.isNotEmpty()) return@withContext Reply(actions.joinToString("\n"), actions)
                    throw ClaudeChat.ChatError("$label sent back an empty reply. Try again.")
                }
                val cut = if (parsed.finish == "length") "\n\n(The reply was too long and got cut off.)" else ""
                return@withContext Reply(all + cut, actions)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }

    /**
     * Whether this model can call tools: asks it to use a harmless clock tool and looks for the
     * call. False if it answers in words instead, or the service says tools aren't supported.
     */
    suspend fun canUseTools(): Boolean = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You can call tools. When a tool fits, call it instead of answering."))
            .put(JSONObject().put("role", "user").put("content", "What time is it? Use the get_current_time tool."))
        val clock = ToolSpec("get_current_time", "Gets the current time.", emptyMap(), emptyList())
        try {
            val parsed = post(request(model, messages, listOf(clock), stream = false, noThinking = local, maxTokens = 256)) {}
            parsed.toolCalls.any { it.name == "get_current_time" }
        } catch (e: ClaudeChat.ChatError) {
            SensingLog.add("Models: tool check for $label failed: ${e.message}")
            false
        }
    }

    /** "Test connection": lists the service's models. Returns what to show; throws [ClaudeChat.ChatError]. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val connection = open(ModelCatalog.modelsUrl(baseUrl), "GET")
        val code = try {
            connection.responseCode
        } catch (e: IOException) {
            throw unreachable(e)
        }
        if (code !in 200..299) throw ClaudeChat.ChatError(httpProblem(code, connection.errorStream?.bufferedReader()?.readText()))
        val ids = parseModelIds(connection.inputStream.bufferedReader().use { it.readText() })
        when {
            ids.isEmpty() -> "Connected. It didn't list any models."
            model.isBlank() -> "Connected: ${ids.size} models, for example ${ids.take(3).joinToString(", ")}. Type one as the model name."
            model in ids -> "Connected, and \"$model\" is there."
            else -> "Connected, but \"$model\" isn't in its list (${ids.take(3).joinToString(", ")}…). Check the model name."
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (!apiKey.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            // OpenRouter asks apps to name themselves; a generic name, nothing about the user.
            setRequestProperty("X-Title", "Ruby")
        }

    private fun post(body: JSONObject, onText: (String) -> Unit): Parsed {
        val connection = open(ModelCatalog.chatUrl(baseUrl), "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw ClaudeChat.ChatError(httpProblem(code, connection.errorStream?.bufferedReader()?.readText()))
            val stream = body.optBoolean("stream")
            val type = connection.contentType.orEmpty()
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (stream && type.contains("event-stream")) {
                    val acc = StreamAccumulator()
                    reader.lineSequence().forEach { line ->
                        if (acc.feed(line)) onText(acc.text.trim())
                    }
                    acc.result()
                } else {
                    parseResponse(JSONObject(reader.readText())).also { onText(it.text.trim()) }
                }
            }
        } catch (e: ClaudeChat.ChatError) {
            throw e
        } catch (e: IOException) {
            throw unreachable(e)
        } catch (e: org.json.JSONException) {
            throw ClaudeChat.ChatError("$label sent back something Ruby couldn't read.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun unreachable(e: IOException) = ClaudeChat.ChatError(
        when (e) {
            is ConnectException, is UnknownHostException ->
                if (local) "The on-phone model isn't running. Try again in a moment." else "Couldn't reach $label. Check the address and that it's on."
            is SocketTimeoutException -> "$label took too long to answer."
            else -> "Lost the connection to $label."
        },
        e,
    )

    private fun httpProblem(code: Int, body: String?): String {
        SensingLog.add("Models: $label answered $code: ${body?.take(300)}")
        return when (code) {
            401, 403 -> "$label refused the API key."
            404 -> "$label doesn't know the model \"$model\" (or the address is wrong)."
            402 -> "$label says the account is out of credit."
            429 -> "$label is busy or the limit was reached. Try again in a bit."
            else -> "$label answered with an error ($code)."
        }
    }

    private suspend fun runTool(toolBox: ToolBox, call: ToolCall, actions: MutableList<String>): String {
        // The safety rules, in code: only low-risk tools run straight from a model's call.
        if (!ModelSafety.mayRun(call.name)) {
            SensingLog.add("Models: $label asked for ${call.name}; not run (needs the user's yes, or not allowed).")
            return ModelSafety.refusal(call.name)
        }
        val input = try {
            jsonToMap(JSONObject(call.arguments.ifBlank { "{}" }))
        } catch (e: org.json.JSONException) {
            return "Error: the arguments weren't valid JSON."
        }
        val outcome = toolBox.run(call.name, input)
        outcome.action?.let { actions += it }
        return if (outcome.isError) "Error: ${outcome.result}" else outcome.result
    }

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class Parsed(val text: String, val toolCalls: List<ToolCall>, val finish: String?)

    /** Builds a reply from streamed "data: {...}" lines, text and tool-call pieces alike. */
    class StreamAccumulator {
        private val sb = StringBuilder()
        private val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
        private var finish: String? = null
        val text: String get() = sb.toString()

        /** Takes one line of the stream; true if the visible text grew. */
        fun feed(line: String): Boolean {
            if (!line.startsWith("data:")) return false
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") return false
            val json = try {
                JSONObject(data)
            } catch (e: org.json.JSONException) {
                return false
            }
            json.optJSONObject("error")?.let { throw ClaudeChat.ChatError("The model reported an error: ${it.optString("message").take(200)}") }
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return false
            choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
            val delta = choice.optJSONObject("delta") ?: return false
            var grew = false
            if (delta.has("content") && !delta.isNull("content")) {
                val piece = delta.optString("content")
                if (piece.isNotEmpty()) {
                    sb.append(piece)
                    grew = true
                }
            }
            delta.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    val slot = calls.getOrPut(c.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                    c.optString("id").takeIf { it.isNotEmpty() && it != "null" }?.let { slot.first.setLength(0); slot.first.append(it) }
                    c.optJSONObject("function")?.let { f ->
                        f.optString("name").takeIf { it.isNotEmpty() && it != "null" }?.let { slot.second.append(it) }
                        if (f.has("arguments") && !f.isNull("arguments")) slot.third.append(f.optString("arguments"))
                    }
                }
            }
            return grew
        }

        fun result() = Parsed(
            text = sb.toString(),
            toolCalls = calls.values.mapIndexed { i, (id, name, args) ->
                ToolCall(id.toString().ifEmpty { "call_$i" }, name.toString(), args.toString())
            }.filter { it.name.isNotEmpty() },
            finish = finish,
        )
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 4

        /** Ruby's messages for the model: her instructions, then the conversation as Claude would see it. */
        fun messagesFor(system: String, history: List<ChatTurn>): JSONArray {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            history.dropWhile { !it.fromUser }.forEach { turn ->
                messages.put(
                    JSONObject().put("role", if (turn.fromUser) "user" else "assistant")
                        .put("content", if (turn.fromUser) turn.sent ?: turn.text else turn.text)
                )
            }
            return messages
        }

        fun request(
            model: String,
            messages: JSONArray,
            tools: List<ToolSpec>,
            stream: Boolean,
            noThinking: Boolean,
            maxTokens: Int = 2_000,
        ): JSONObject = JSONObject().apply {
            put("model", model.ifBlank { "default" })
            put("messages", messages)
            put("stream", stream)
            put("max_tokens", maxTokens)
            if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(toolJson(it)) } })
            // llama.cpp: no thinking (Qwen 3.5 and Gemma 4 think by default, which is slow on a phone).
            if (noThinking) put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
        }

        fun toolJson(spec: ToolSpec): JSONObject = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().apply { spec.properties.forEach { (k, v) -> put(k, toJson(v)) } })
                            .put("required", JSONArray(spec.required)),
                    ),
            )

        private fun toJson(value: Any?): Any? = when (value) {
            is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
            is List<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
            else -> value
        }

        fun parseResponse(json: JSONObject): Parsed {
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                ?: throw ClaudeChat.ChatError(json.optJSONObject("error")?.optString("message")?.take(200)?.let { "The model reported an error: $it" } ?: "No answer came back.")
            val message = choice.optJSONObject("message") ?: JSONObject()
            val text = if (message.isNull("content")) "" else message.optString("content")
            val calls = message.optJSONArray("tool_calls")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    val f = c.optJSONObject("function") ?: JSONObject()
                    ToolCall(c.optString("id").ifEmpty { "call_$i" }, f.optString("name"), f.optString("arguments"))
                }
            }.orEmpty().filter { it.name.isNotEmpty() }
            return Parsed(text, calls, choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" })
        }

        fun assistantWithTools(parsed: Parsed): JSONObject = JSONObject()
            .put("role", "assistant")
            .put("content", parsed.text.ifBlank { JSONObject.NULL })
            .put(
                "tool_calls",
                JSONArray().apply {
                    parsed.toolCalls.forEach { c ->
                        put(
                            JSONObject().put("id", c.id).put("type", "function")
                                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments.ifBlank { "{}" }))
                        )
                    }
                },
            )

        fun parseModelIds(body: String): List<String> = try {
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotEmpty() } }
        } catch (e: org.json.JSONException) {
            emptyList()
        }

        fun jsonToMap(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
            when (val v = json.get(key)) {
                JSONObject.NULL -> null
                is JSONObject -> jsonToMap(v)
                is JSONArray -> (0 until v.length()).map { v.get(it) }
                else -> v
            }
        }

        private fun join(parts: List<String>) = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
    }
}
