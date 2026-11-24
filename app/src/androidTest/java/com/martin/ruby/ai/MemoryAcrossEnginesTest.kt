package com.martin.ruby.ai

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.martin.ruby.chat.ApiKeyStore
import com.martin.ruby.chat.ChatSession
import com.martin.ruby.memory.MemoryRecall
import com.martin.ruby.memory.MemoryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ruby's memory is one, whichever AI engine answers: a message saved in Free mode is found
 * in Claude mode, and the other way round, through the real chat. Switching never loses or
 * hides anything. Changes memory and settings (and cleans up), so it runs on the emulator,
 * where neither engine can answer (no Gemini Nano, no Claude key): each message is saved
 * before the reply is tried, which is the path being checked.
 */
@RunWith(AndroidJUnit4::class)
class MemoryAcrossEnginesTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = AiSettings(context)
    private val memory = MemoryStore(context)

    private val freeWord = "zebrafish7731"
    private val claudeWord = "quokka4482"

    @Test
    fun aMemoryMadeInOneEngine_isFoundInTheOther() = runBlocking {
        assumeTrue(
            "Changes memory and settings: emulator only",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic"),
        )
        assumeTrue("Needs no Claude key, so no reply goes out", !ApiKeyStore(context).hasKey())
        val before = settings.current()
        instrumentation.runOnMainSync { ChatSession.checkKey(context) }
        try {
            val countAtStart = memory.count().first()

            // Free mode: the message goes into Ruby's one memory.
            use(AiEngine.FREE)
            send("Memory check in Free mode: my telescope is called $freeWord.")

            // Claude mode finds it, and the count didn't drop when switching.
            use(AiEngine.CLAUDE)
            assertTrue("Switching engines never loses memories", memory.count().first() >= countAtStart + 1)
            assertTrue("Claude mode finds the Free mode memory", recalled(freeWord))
            send("Memory check in Claude mode: my favourite planet is $claudeWord.")

            // And back: Free mode finds what was saved in Claude mode.
            use(AiEngine.FREE)
            assertTrue("Free mode finds the Claude mode memory", recalled(claudeWord))
            assertTrue(memory.count().first() >= countAtStart + 2)

            // Free mode's own messages carry the memories along, as Claude's do.
            send("What did I say $freeWord was?")
            val asked = ChatSession.ui.value.turns.last { it.fromUser }
            assertTrue("The message took the memory along: ${asked.memoriesUsed}", asked.memoriesUsed >= 1)
            assertTrue(asked.sent.orEmpty(), asked.sent.orEmpty().contains("my telescope is called $freeWord"))
        } finally {
            settings.save(before)
            for (word in listOf(freeWord, claudeWord)) memory.search(word).first().forEach { memory.delete(it) }
            instrumentation.runOnMainSync {
                ChatSession.dismissError()
                ChatSession.newConversation()
            }
        }
    }

    private suspend fun use(engine: AiEngine) {
        settings.save(engine)
        repeat(100) {
            if (ChatSession.ui.value.engine == engine) return
            delay(50)
        }
        // A new conversation, so recall searches everything (it leaves out the current one).
        instrumentation.runOnMainSync { ChatSession.newConversation() }
    }

    private suspend fun send(text: String) {
        instrumentation.runOnMainSync {
            ChatSession.dismissError()
            ChatSession.newConversation()
            ChatSession.send(context, text)
        }
        repeat(200) {
            if (!ChatSession.ui.value.sending) return
            delay(50)
        }
    }

    private suspend fun recalled(word: String): Boolean =
        memory.recall("What about $word?", exclude = emptySet()).any { r ->
            r is MemoryRecall.Recalled.Message && r.message.text.contains(word)
        } && memory.search(word).first().isNotEmpty()
}
