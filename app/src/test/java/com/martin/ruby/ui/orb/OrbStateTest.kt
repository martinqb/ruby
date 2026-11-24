package com.martin.ruby.ui.orb

import com.martin.ruby.chat.ChatUi
import com.martin.ruby.voice.VoiceUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbStateTest {

    @Test
    fun whichStateShows() {
        val idle = VoiceUi()
        val chat = ChatUi(hasKey = true)
        assertEquals(OrbState.IDLE, orbStateFor(chat, idle))
        assertEquals(OrbState.LISTENING, orbStateFor(chat, idle.copy(listening = true)))
        // Sent, nothing back yet.
        assertEquals(OrbState.THINKING, orbStateFor(chat.copy(sending = true), idle))
        // Reply arriving as text only: her words appearing.
        assertEquals(OrbState.SPEAKING, orbStateFor(chat.copy(sending = true, replying = "Neptune is"), idle))
        // Reply to be read aloud, first sentence not complete yet: still thinking.
        assertEquals(OrbState.THINKING, orbStateFor(chat.copy(sending = true, replying = "Neptune is"), idle.copy(readingReply = true)))
        // Asked to speak; Kokoro loading, then making the first words, then heard.
        val asked = idle.copy(readingReply = true, speaking = true)
        assertEquals(OrbState.CONNECTING, orbStateFor(chat.copy(sending = true), asked.copy(loading = true)))
        assertEquals(OrbState.THINKING, orbStateFor(chat.copy(sending = true), asked))
        assertEquals(OrbState.SPEAKING, orbStateFor(chat.copy(sending = true), asked.copy(sounding = true)))
        // Still talking after the reply finished arriving.
        assertEquals(OrbState.SPEAKING, orbStateFor(chat, idle.copy(speaking = true, sounding = true)))
        // Problems show once nothing else is going on.
        assertEquals(OrbState.ERROR, orbStateFor(chat.copy(error = "No internet"), idle))
        assertEquals(OrbState.ERROR, orbStateFor(chat, idle.copy(problem = "The Kokoro voice stopped working")))
        assertEquals(OrbState.LISTENING, orbStateFor(chat.copy(error = "No internet"), idle.copy(listening = true)))
    }

    @Test
    fun statesBlendInAtTheReferenceSpeeds() {
        val mix = StateMix(OrbState.IDLE)
        repeat(12) { mix.update(OrbState.SPEAKING, 1 / 60f) } // 0.2 s
        assertTrue("speaking mostly in after 0.2 s: ${mix.weight(OrbState.SPEAKING)}", mix.weight(OrbState.SPEAKING) > 0.9f)
        repeat(36) { mix.update(OrbState.IDLE, 1 / 60f) } // 0.6 s
        assertTrue("idle mostly back after 0.6 s: ${mix.weight(OrbState.IDLE)}", mix.weight(OrbState.IDLE) > 0.9f)
        val total = OrbState.entries.sumOf { mix.weight(it).toDouble() }
        assertEquals(1.0, total, 1e-4)
    }

    @Test
    fun eachStatesMadeUpLevelStaysInItsRange() {
        for (i in 0..2000) {
            val t = i / 100.0
            assertEquals(0f, OrbMath.energy(OrbState.IDLE, t))
            assertTrue(OrbMath.energy(OrbState.LISTENING, t) in 0.4f..0.9f)
            assertTrue(OrbMath.energy(OrbState.SPEAKING, t) in 0.3f..0.7f)
            assertTrue(OrbMath.energy(OrbState.THINKING, t) in 0.24f..0.44f)
            assertTrue(OrbMath.energy(OrbState.CONNECTING, t) in 0.12f..0.22f)
        }
    }

    @Test
    fun levelRisesQuicklyAndFallsSlowly() {
        val anim = OrbAnimator(OrbState.SPEAKING)
        repeat(6) { anim.step(OrbState.SPEAKING, 1f, 1 / 60f) } // 0.1 s of loud
        val up = anim.level
        assertTrue("rises fast: $up", up > 0.6f)
        repeat(6) { anim.step(OrbState.SPEAKING, 0f, 1 / 60f) } // 0.1 s of quiet
        assertTrue("falls slower: ${anim.level}", anim.level > up * 0.6f)
        // No live level: the state's own motion.
        repeat(120) { anim.step(OrbState.THINKING, -1f, 1 / 60f) }
        assertTrue(anim.level in 0.2f..0.5f)
    }

    @Test
    fun calmStatesCanUseFewerFrames() {
        val anim = OrbAnimator(OrbState.IDLE)
        anim.step(OrbState.IDLE, -1f, 1 / 60f)
        assertTrue(anim.calm)
        anim.step(OrbState.SPEAKING, -1f, 1 / 60f)
        assertFalse(anim.calm)
        repeat(120) { anim.step(OrbState.ERROR, -1f, 1 / 60f) }
        assertTrue(anim.calm)
    }

    @Test
    fun keyframesLoopAndEase() {
        val stops = floatArrayOf(0f, 0.5f, 1f)
        val values = floatArrayOf(0f, 1f, 0f)
        assertEquals(0f, OrbMath.keyframes(0f, stops, values), 1e-6f)
        assertEquals(1f, OrbMath.keyframes(0.5f, stops, values), 1e-6f)
        assertEquals(0.5f, OrbMath.keyframes(0.25f, stops, values), 1e-6f)
        assertEquals(OrbMath.keyframes(0.3f, stops, values), OrbMath.keyframes(7.3f, stops, values), 1e-5f)
        // Eased: slower near the keyframes than a straight line.
        assertTrue(OrbMath.keyframes(0.05f, stops, values) < 0.1f)
    }

    @Test
    fun paletteColorsFollowTheReference() {
        val p = AuroraPalette.normal()
        // Fully opaque, and between the two mixed colors.
        assertEquals(0xFF, (p.green ushr 24) and 0xFF)
        assertEquals(AuroraPalette.FROM, AuroraPalette.mix(AuroraPalette.FROM, AuroraPalette.TO, 0f))
        assertEquals(AuroraPalette.TO, AuroraPalette.mix(AuroraPalette.FROM, AuroraPalette.TO, 1f))
        // Thinking leans violet: more blue-red than green compared with normal.
        val t = AuroraPalette.thinking()
        assertTrue(((t.green shr 16) and 0xFF) > ((p.green shr 16) and 0xFF))
        assertEquals(0xFFF43F5E.toInt(), AuroraPalette.error().glow)
    }
}
