package com.martin.ruby.ai.models

import com.anthropic.models.beta.messages.BetaTool
import com.martin.ruby.ai.AiEngine
import com.martin.ruby.chat.ChatTurn
import com.martin.ruby.chat.ClaudeChat
import com.martin.ruby.chat.ToolBox
import com.martin.ruby.chat.ToolOutcome
import com.martin.ruby.reminders.ReminderTools
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Other AI models: the catalog, memory check, safety rules, and the OpenAI-compatible chat. */
class ModelsTest {

    // ---- Catalog

    @Test
    fun thePresets_andTheUncensoredOneOnlyInMartinsBuild() {
        val mine = ModelCatalog.localPresets(personalBuild = true)
        assertEquals(listOf("Gemma 4 E4B Uncensored Aggressive", "Qwen 3.5 9B"), mine.map { it.name })
        val public = ModelCatalog.localPresets(personalBuild = false)
        assertEquals(listOf("Qwen 3.5 9B"), public.map { it.name })
        // The links and file names checked against Hugging Face (Nov 27, 2026).
        val gemma = ModelCatalog.GEMMA_4_E4B_UNCENSORED
        assertTrue(gemma.url.endsWith("/resolve/main/${gemma.fileName}"))
        assertEquals(5_335_285_728, gemma.bytes)
        val qwen = ModelCatalog.QWEN_3_5_9B
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf", qwen.url)
        assertEquals(5_680_522_464, qwen.bytes)
        listOf(gemma, qwen).forEach { assertEquals(64, it.sha256!!.length) }
    }

    @Test
    fun aGgufLink_isChecked() {
        val ok = ModelCatalog.fromLink("https://huggingface.co/x/y/resolve/main/Tiny-Model-Q4_K_M.gguf").getOrThrow()
        assertEquals("Tiny-Model-Q4_K_M.gguf", ok.fileName)
        assertEquals("Tiny-Model-Q4_K_M", ok.name)
        assertTrue(ok.custom)
        assertEquals("My tiny one", ModelCatalog.fromLink("https://h.co/a/b.gguf", "My tiny one").getOrThrow().name)
        assertEquals("The link must start with https://.", ModelCatalog.fromLink("http://h.co/a.gguf").exceptionOrNull()?.message)
        assertEquals("The link must point to a .gguf file.", ModelCatalog.fromLink("https://h.co/a.bin").exceptionOrNull()?.message)
        assertTrue(ModelCatalog.fromLink("not a link at all").isFailure)
    }

    @Test
    fun cloudAndPcPresets_andTheirAddresses() {
        assertEquals(listOf("OpenRouter", "My PC with Ollama", "My PC with LM Studio"), ModelCatalog.REMOTE_PRESETS.map { it.name })
        assertEquals("https://openrouter.ai/api/v1", ModelCatalog.REMOTE_PRESETS[0].baseUrl)
        assertTrue(ModelCatalog.REMOTE_PRESETS[0].needsKey)
        assertFalse(ModelCatalog.REMOTE_PRESETS[1].needsKey)
        assertEquals("http://<PC IP>:11434/v1", ModelCatalog.REMOTE_PRESETS[1].baseUrl)
        assertEquals("http://<PC IP>:1234/v1", ModelCatalog.REMOTE_PRESETS[2].baseUrl)
        // The placeholder must be filled in before use.
        assertTrue(ModelCatalog.baseUrlProblem("http://<PC IP>:11434/v1")!!.contains("<PC IP>"))
        assertNull(ModelCatalog.baseUrlProblem("http://100.101.102.103:11434/v1"))
        assertEquals("http://h:1/v1/chat/completions", ModelCatalog.chatUrl("http://h:1/v1/"))
        assertEquals("http://h:1/v1/models", ModelCatalog.modelsUrl("http://h:1/v1"))
    }

    // ---- Memory

    @Test
    fun theRamCheck() {
        val gb = 1_000_000_000L
        // Martin's S26 Ultra: about 11.4 GB in all.
        assertEquals(RamFit.FITS, RamCheck.fit(5_680_522_464, totalRam = 11_400_000_000, availableRam = 8 * gb))
        assertEquals(RamFit.TIGHT, RamCheck.fit(5_680_522_464, totalRam = 11_400_000_000, availableRam = 4_500_000_000))
        assertEquals(RamFit.TOO_BIG, RamCheck.fit(5_680_522_464, totalRam = 8 * gb, availableRam = 7 * gb))
        assertNull(RamCheck.warning(RamFit.FITS, 5 * gb, 12 * gb))
        assertTrue(RamCheck.warning(RamFit.TOO_BIG, 5 * gb, 8 * gb)!!.contains("too much for this phone (8.0 GB in all)"))
        assertEquals("5.7 GB", RamCheck.gb(5_680_522_464))
    }

    // ---- Safety, in code

    @Test
    fun onlyLowRiskTools_runFromAModel() {
        assertTrue(ModelSafety.mayRun(ReminderTools.SET))
        assertTrue(ModelSafety.mayRun(ReminderTools.LIST))
        assertTrue(ModelSafety.mayRun(ReminderTools.CANCEL))
        for (tool in listOf("start_car", "unlock_phone", "spend_money", "save_calendar_event", "control_device", "anything_new")) {
            assertFalse(tool, ModelSafety.mayRun(tool))
        }
        assertTrue(ModelSafety.refusal("save_calendar_event").contains("needs the user's yes"))
        assertEquals(listOf(ReminderTools.SET), ModelSafety.offered(listOf(ReminderTools.SET, "unlock_phone")))
        assertEquals(listOf("Web search", "Skills", "Thinking"), ModelSafety.CLAUDE_ONLY)
    }

    // ---- Same instructions and memory as Claude

    @Test
    fun ruby_isTheSameRubyWithEveryModel() {
        assertTrue(ModelEngine.system(toolsOn = true).startsWith(ClaudeChat.SYSTEM_PROMPT))
        assertTrue(ModelEngine.system(toolsOn = false).startsWith(ClaudeChat.SYSTEM_PROMPT))
        assertTrue(ModelEngine.system(toolsOn = false).contains("Never say a reminder is set"))
        // Each message goes as Claude sees it: with the phone's notes and memories.
        val turns = listOf(
            ChatTurn(fromUser = true, text = "hi", sent = "[Right now: Still]\n[From Ruby's memory: ...]\n\nhi"),
            ChatTurn(fromUser = false, text = "Hello!"),
        )
        val messages = OpenAiChat.messagesFor("SYS", turns)
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("[Right now: Still]\n[From Ruby's memory: ...]\n\nhi", messages.getJSONObject(1).getString("content"))
        assertEquals("assistant", messages.getJSONObject(2).getString("role"))
    }

    @Test
    fun theActiveEngine_isShownWithItsModel() {
        val qwen = ModelCatalog.QWEN_3_5_9B
        val choices = ModelChoices(local = listOf(qwen), chosenLocal = qwen.id)
        assertEquals("Qwen 3.5 9B (on phone)", ModelEngine.label(AiEngine.LOCAL, choices))
        val pc = ModelCatalog.REMOTE_PRESETS[1].copy(model = "qwen3.5:9b")
        assertEquals("qwen3.5:9b (My PC with Ollama)", ModelEngine.label(AiEngine.REMOTE, ModelChoices(remote = listOf(pc), chosenRemote = pc.id)))
        assertNull(ModelEngine.label(AiEngine.CLAUDE, choices))
        assertTrue(AiEngine.LOCAL.usesModelsScreen && AiEngine.REMOTE.usesModelsScreen)
        assertFalse(AiEngine.CLAUDE.usesModelsScreen || AiEngine.FREE.usesModelsScreen)
    }

    @Test
    fun savedEntries_roundTrip() {
        val custom = ModelCatalog.fromLink("https://h.co/a/Tiny.gguf").getOrThrow().copy(bytes = 123)
        assertEquals(listOf(custom), ModelStore.decodeLocal(ModelStore.encodeLocal(listOf(custom))))
        val remote = listOf(RemoteEndpoint("x", "Mine", "http://10.0.0.2:1234/v1", "llama", needsKey = false))
        assertEquals(remote, ModelStore.decodeRemote(ModelStore.encodeRemote(remote)))
        assertEquals(ModelCatalog.REMOTE_PRESETS, ModelStore.decodeRemote("not json"))
    }

    // ---- Requests and answers

    @Test
    fun theRequest_hasTheTools_andNoThinkingOnThePhone() {
        val spec = ReminderTools.SPECS.first { it.name == ReminderTools.SET }
        val body = OpenAiChat.request("m", JSONArray(), listOf(spec), stream = true, noThinking = true)
        val fn = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertEquals("set_reminder", fn.getString("name"))
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))
        assertTrue(fn.getJSONObject("parameters").getJSONObject("properties").has("at"))
        assertEquals(JSONArray(listOf("text")).toString(), fn.getJSONObject("parameters").getJSONArray("required").toString())
        assertFalse(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
        assertFalse(OpenAiChat.request("m", JSONArray(), emptyList(), stream = false, noThinking = false).has("tools"))
        // Claude's and the other models' reminder tools come from the same specs.
        assertEquals(ReminderTools.SPECS.map { it.name }, ReminderTools.DEFINITIONS.map { it.name() })
    }

    @Test
    fun aStreamedReply_withTextAndAToolCallInPieces() {
        val acc = OpenAiChat.StreamAccumulator()
        listOf(
            """data: {"choices":[{"delta":{"content":"On it"}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"set_reminder","arguments":"{\"text\":"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"milk\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        ).forEach { acc.feed(it) }
        val r = acc.result()
        assertEquals("On it", r.text)
        assertEquals(listOf(OpenAiChat.ToolCall("c1", "set_reminder", "{\"text\":\"milk\"}")), r.toolCalls)
        assertEquals("tool_calls", r.finish)
    }

    // ---- The whole exchange, against a stand-in server

    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    /** A stand-in OpenAI-compatible server answering with [answers] in turn; returns its base URL and the requests it got. */
    private fun serve(vararg answers: String): Pair<String, MutableList<JSONObject>> {
        val got = mutableListOf<JSONObject>()
        var i = 0
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/v1/chat/completions") { ex ->
            got += JSONObject(ex.requestBody.bufferedReader().readText())
            val body = answers[minOf(i++, answers.lastIndex)].toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/v1" to got
    }

    private class FakeTools : ToolBox {
        val ran = mutableListOf<Pair<String, Map<*, *>>>()
        override val tools: List<BetaTool> = ReminderTools.DEFINITIONS
        override suspend fun run(name: String, input: Map<*, *>): ToolOutcome {
            ran += name to input
            return ToolOutcome("Reminder 7 set for 5:00 PM: ${input["text"]}", action = "Reminder set: ${input["text"]}")
        }
    }

    private fun toolCall(name: String, args: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"t1","type":"function","function":{"name":"$name","arguments":${JSONObject.quote(args)}}}]},"finish_reason":"tool_calls"}]}"""

    private fun text(t: String) = """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(t)}},"finish_reason":"stop"}]}"""

    private val ask = listOf(ChatTurn(fromUser = true, text = "Remind me to buy milk at 5 pm", sent = "[Right now: Still]\nRemind me to buy milk at 5 pm"))

    @Test
    fun aReminder_throughToolCalling() = runBlocking {
        val (url, got) = serve(toolCall("set_reminder", """{"text":"buy milk","at":"2026-11-28T17:00"}"""), text("Done: I'll remind you at 5."))
        val tools = FakeTools()
        val reply = OpenAiChat(url, null, "m", "Test model").reply("SYS", ask, tools) {}
        assertEquals("Done: I'll remind you at 5.", reply.text)
        assertEquals(listOf("Reminder set: buy milk"), reply.actions)
        assertEquals("set_reminder", tools.ran.single().first)
        assertEquals("buy milk", tools.ran.single().second["text"])
        // The tool's result went back to the model, tied to its call.
        val second = got[1].getJSONArray("messages")
        val toolMessage = second.getJSONObject(second.length() - 1)
        assertEquals("tool", toolMessage.getString("role"))
        assertEquals("t1", toolMessage.getString("tool_call_id"))
        assertTrue(toolMessage.getString("content").startsWith("Reminder 7 set"))
    }

    @Test
    fun aHighRiskCall_isNeverRun_evenIfTheModelTries() = runBlocking {
        val (url, got) = serve(toolCall("unlock_phone", "{}"), text("I can't do that with this model."))
        val tools = FakeTools()
        val reply = OpenAiChat(url, null, "m", "Uncensored model").reply("SYS", ask, tools) {}
        assertTrue("nothing ran", tools.ran.isEmpty())
        assertEquals("I can't do that with this model.", reply.text)
        val back = got[1].getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }
        assertTrue(back.getString("content").startsWith("Not done"))
        // And high-risk tools aren't even offered.
        val offered = got[0].getJSONArray("tools")
        assertEquals(ReminderTools.SPECS.size, offered.length())
    }

    @Test
    fun aModelWithoutTools_chatsWithoutThem() = runBlocking {
        val (url, got) = serve(text("Hello Martin."))
        val reply = OpenAiChat(url, null, "m", "Plain model").reply("SYS", ask, toolBox = null) {}
        assertEquals("Hello Martin.", reply.text)
        assertFalse(got[0].has("tools"))
    }

    @Test
    fun theToolCheck() = runBlocking {
        val (url, _) = serve(toolCall("get_current_time", "{}"))
        assertTrue(OpenAiChat(url, null, "m", "x").canUseTools())
        stop()
        val (url2, _) = serve(text("It's 5 pm."))
        assertFalse(OpenAiChat(url2, null, "m", "x").canUseTools())
    }

    @Test
    fun aServiceThatIsntThere_saysSoPlainly() = runBlocking {
        val e = runCatching { OpenAiChat("http://127.0.0.1:9/v1", null, "m", "My PC").reply("SYS", ask, null) {} }.exceptionOrNull()
        assertTrue(e is ClaudeChat.ChatError)
        assertEquals("Couldn't reach My PC. Check the address and that it's on.", e!!.message)
    }
}
