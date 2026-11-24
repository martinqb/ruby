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
        repeat(120) { anim.step(OrbState.CONNECTING, -1f, 1 / 60f) }
        assertTrue(anim.calm)
        // Error's quick double pulse needs every frame.
        repeat(120) { anim.step(OrbState.ERROR, -1f, 1 / 60f) }
        assertFalse(anim.calm)
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
        // Error is amber, not red, so it never clashes with a red orb.
        val error = AuroraPalette.error()
        assertEquals(0xFFFF9F1C.toInt(), error.glow)
        assertEquals(0xFFFF9F1C.toInt(), error.green)
        for (c in listOf(error.hi, error.green, error.cyan, error.pink, error.glow)) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            assertTrue("amber or orange, not red or pink: ${Integer.toHexString(c)}", r > g && g > b && g >= r * 0.5)
        }
    }

    @Test
    fun rubyThemeIsRed_andErrorStaysAmberOnEveryTheme() {
        fun hue(c: Int): Float {
            val hsv = FloatArray(3)
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            if (d == 0f) return 0f
            val h = when (max) {
                r -> 60f * (((g - b) / d) % 6f)
                g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            hsv[0] = if (h < 0) h + 360f else h
            return hsv[0]
        }
        fun isRed(c: Int) = hue(c).let { it >= 320f || it <= 10f }
        val ruby = OrbTheme.RUBY.normal()
        for (c in listOf(ruby.green, ruby.cyan, ruby.pink, ruby.glow)) assertTrue("ruby band should be red: ${Integer.toHexString(c)}", isRed(c))
        // Its sky is a wine-colored night: dark and red-leaning.
        assertTrue(isRed(ruby.skyTop) && ((ruby.skyTop shr 16) and 0xFF) < 0x40)
        // Thinking leans toward plum: more blue than normal.
        assertTrue((OrbTheme.RUBY.thinking().glow and 0xFF) > (ruby.glow and 0xFF))
        // Aurora is unchanged.
        assertEquals(AuroraPalette.normal(), OrbTheme.AURORA.normal())
        // Error is the same amber on every theme, well away from ruby red.
        val amber = hue(AuroraPalette.AMBER)
        assertTrue("amber hue $amber", amber in 25f..45f)
        for (theme in OrbTheme.entries) {
            val diff = kotlin.math.abs(hue(theme.normal().glow) - amber).let { minOf(it, 360f - it) }
            assertTrue("$theme's glow should be far from amber (hue gap $diff)", diff > 45f)
        }
    }

    @Test
    fun errorHasItsOwnDoublePulse() {
        // Over one cycle: two quick peaks, then a long quiet rest.
        val samples = (0 until 160).map { OrbMath.errorPulse(it / 100.0) } // 1.6 s at 100 per second
        var peaks = 0
        for (i in 1 until samples.size - 1) {
            if (samples[i] > 0.9f && samples[i] >= samples[i - 1] && samples[i] > samples[i + 1]) peaks++
        }
        assertEquals("two pulses per cycle", 2, peaks)
        assertTrue("quiet between them", samples[19] < 0.3f)
        assertTrue("then a rest", samples.drop(50).all { it < 0.01f })
        // It repeats.
        assertEquals(OrbMath.errorPulse(0.08), OrbMath.errorPulse(0.08 + OrbMath.ERROR_CYCLE * 3), 1e-4f)
        // No other state has this rhythm: their motion is continuous waves.
        assertTrue(OrbMath.energy(OrbState.ERROR, 0.08) > 0.6f)
        assertTrue(OrbMath.energy(OrbState.ERROR, 1.0) < 0.2f)
    }
}
