package com.martin.ruby.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MemoryPackTest {

    private fun message(i: Int, text: String, context: String? = "Driving") =
        MemoryMessage(id = i.toLong(), timeMillis = 1_000L * i, role = MemoryMessage.ROLE_USER, text = text, context = context)

    @Test
    fun packAndUnpack_keepsEverythingButTheIds() {
        val messages = listOf(
            message(1, "Parked on level 3, row C"),
            message(2, "Ünïcødé, emoji 🚗, and \"quotes\"", context = null),
            message(3, ""),
            message(4, "x".repeat(200_000)), // longer than DataOutputStream.writeUTF allows
        )
        val back = MemoryPack.unpack(MemoryPack.pack(messages))
        assertEquals(messages.map { it.copy(id = 0) }, back)
    }

    @Test
    fun empty_isFine() {
        assertEquals(emptyList<MemoryMessage>(), MemoryPack.unpack(MemoryPack.pack(emptyList())))
    }

    @Test
    fun textCompresses() {
        val messages = List(200) { message(it, "Remember the tyre pressure is 36 psi and the oil change is due in March, note $it") }
        val raw = messages.sumOf { it.text.length }
        assertTrue(MemoryPack.pack(messages).size < raw / 3)
    }

    @Test
    fun damaged_isRefused() {
        val packed = MemoryPack.pack(listOf(message(1, "hello")))
        packed[packed.size / 2] = (packed[packed.size / 2] + 1).toByte()
        try {
            MemoryPack.unpack(packed)
            fail("Expected an error")
        } catch (expected: IOException) {
        }
    }
}
