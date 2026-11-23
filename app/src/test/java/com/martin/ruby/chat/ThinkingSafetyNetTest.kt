package com.martin.ruby.chat

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.messages.BetaMessage
import com.martin.ruby.reminders.ReminderTools
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

/**
 * Ruby's chat failed with "Invalid `signature` in `thinking` block ... The `tools` list differs"
 * after the first turn: the tools were rebuilt for every message. These check that the tools
 * can't change, and that if Anthropic still refuses earlier thinking, Ruby recovers once and
 * otherwise says something friendly instead of showing the raw error.
 */
class ThinkingSafetyNetTest {

    // The tools

    @Test
    fun toolDefinitions_areTheSameEveryTime_andHoldNothingThatChanges() {
        val json = jsonMapper().writeValueAsString(ReminderTools.DEFINITIONS)
        assertEquals(json, jsonMapper().writeValueAsString(ReminderTools.DEFINITIONS))
        assertSame(ReminderTools.DEFINITIONS, ReminderTools.DEFINITIONS)
        // Saved places used to be listed in set_reminder's description; now nothing lists them.
        assertFalse(json.contains("saved places: "))
        assertTrue(json.contains("as listed in the \\\"Saved places\\\" note"))
        assertEquals(listOf("set_reminder", "list_reminders", "cancel_reminder"), ReminderTools.DEFINITIONS.map { it.name() })
    }

    // Recognising the error, and stripping thinking

    @Test
    fun theThinkingRefusal_isRecognised_andNothingElse() {
        assertTrue(ClaudeChat.isThinkingRejection(THINKING_400))
        assertFalse(ClaudeChat.isThinkingRejection("messages.0: max_tokens is too large"))
        assertFalse(ClaudeChat.isThinkingRejection(null))
    }

    @Test
    fun strippingThinking_keepsTextAndToolCalls() {
        val message = assistant(THINKING_BLOCK, TEXT_BLOCK, """{"type":"tool_use","id":"toolu_1","name":"list_reminders","input":{}}""")
        val stripped = ClaudeChat.withoutThinking(message)
        assertEquals(listOf("text", "tool_use"), types(stripped))
        assertEquals(listOf("thinking", "text", "tool_use"), types(message)) // the original is untouched
    }

    // The safety net, against a stand-in for Anthropic's server

    private val bodies = Collections.synchronizedList(mutableListOf<String>())
    private var server: HttpServer? = null

    /** Starts a server that answers each request with the next of [answers] (status to body). */
    private fun serve(vararg answers: Pair<Int, String>): String {
        val queue = ArrayDeque(answers.toList())
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            val (status, body) = synchronized(queue) { queue.removeFirstOrNull() ?: (500 to """{"type":"error"}""") }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", if (status == 200) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    /** A conversation where Ruby's first answer carries a thinking block, then a new message. */
    private fun conversation(): List<ChatTurn> {
        val first = assistant(THINKING_BLOCK, TEXT_BLOCK)
        return listOf(
            ChatTurn(fromUser = true, text = "Hi", sent = "[Right now: noon]\nHi"),
            ChatTurn(fromUser = false, text = "Hello!", transcript = ReplyTranscript(emptyList(), first)),
            ChatTurn(fromUser = true, text = "Remind me later", sent = "[Right now: 12:01 PM]\nRemind me later"),
        )
    }

    @Test
    fun aThinkingRefusal_isRetriedOnceWithoutThinking() = runBlocking {
        val chat = ClaudeChat("test-key", serve(400 to errorBody(THINKING_400), 200 to STREAMED_REPLY))
        val reply = chat.reply(conversation(), NoTools) {}
        assertEquals("Sure, what time?", reply.text)
        assertTrue("The caller is told to drop earlier thinking", reply.thinkingStripped)
        assertEquals(2, bodies.size)
        assertTrue("The first request sent the earlier thinking", bodies[0].contains("\"type\":\"thinking\""))
        assertFalse("The retry sent no thinking", bodies[1].contains("\"type\":\"thinking\""))
        assertTrue("The retry kept the earlier text", bodies[1].contains("Hello!"))
        // Same tools in both requests.
        assertEquals(toolsOf(bodies[0]), toolsOf(bodies[1]))
        chat.close()
    }

    @Test
    fun ifTheRetryFailsToo_theUserGetsAFriendlySentence() = runBlocking {
        val chat = ClaudeChat("test-key", serve(400 to errorBody(THINKING_400), 400 to errorBody(THINKING_400)))
        try {
            chat.reply(conversation(), NoTools) {}
            fail("Should have failed")
        } catch (e: ClaudeChat.ChatError) {
            assertEquals(ClaudeChat.LOST_TRAIN_OF_THOUGHT, e.message)
        }
        assertEquals("Only one retry", 2, bodies.size)
        chat.close()
    }

    @Test
    fun otherBadRequests_arentRetried_andNeverShowJson() = runBlocking {
        val chat = ClaudeChat("test-key", serve(400 to errorBody("messages: something else is wrong")))
        try {
            chat.reply(conversation(), NoTools) {}
            fail("Should have failed")
        } catch (e: ClaudeChat.ChatError) {
            assertEquals(ClaudeChat.LOST_TRAIN_OF_THOUGHT, e.message)
            assertFalse(e.message!!.contains("{"))
        }
        assertEquals(1, bodies.size)
        chat.close()
    }

    @Test
    fun theDebugSwitch_changesOnlyTheFirstRequestsTools() = runBlocking {
        val chat = ClaudeChat("test-key", serve(400 to errorBody(THINKING_400), 200 to STREAMED_REPLY))
        val reply = chat.reply(conversation(), NoTools, alterToolsOnce = true) {}
        assertEquals("Sure, what time?", reply.text)
        assertTrue(toolsOf(bodies[0]).contains("(changed)"))
        assertEquals(jsonMapper().writeValueAsString(ReminderTools.DEFINITIONS), toolsOf(bodies[1]))
        chat.close()
    }

    @Test
    fun strippedTranscripts_carryNoThinking() {
        val transcript = conversation()[1].transcript!!
        assertEquals(listOf("text"), types(transcript.withoutThinking().final!!))
    }

    /** A tool box with the real (fixed) definitions that never runs anything. */
    private object NoTools : ToolBox {
        override val tools = ReminderTools.DEFINITIONS
        override suspend fun run(name: String, input: Map<*, *>) = ToolOutcome("unused")
    }

    private companion object {
        const val THINKING_400 = "messages.1.content.0: Invalid `signature` in `thinking` block. The block is bound to a " +
            "different conversation. The `tools` list differs from the one this block was created with."
        const val THINKING_BLOCK = """{"type":"thinking","thinking":"","signature":"c2lnbmF0dXJl"}"""
        const val TEXT_BLOCK = """{"type":"text","text":"Hello!","citations":null}"""

        fun assistant(vararg blocks: String): BetaMessage = jsonMapper().readValue(
            """{"id":"msg_0","type":"message","role":"assistant","model":"claude-opus-5-5","container":null,
               "context_management":null,"stop_details":null,"stop_reason":"end_turn","stop_sequence":null,
               "usage":{"input_tokens":5,"output_tokens":5},"content":[${blocks.joinToString(",")}]}""",
            BetaMessage::class.java,
        )

        fun types(message: BetaMessage): List<String> =
            jsonMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(message)["content"].map { it["type"].asText() }

        fun toolsOf(body: String) = jsonMapper().readTree(body)["tools"].toString()

        fun errorBody(message: String) =
            jsonMapper().writeValueAsString(mapOf("type" to "error", "error" to mapOf("type" to "invalid_request_error", "message" to message)))

        val STREAMED_REPLY = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Sure, what time?"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}""",
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
    }
}
