package com.martin.ruby.ui.orb

import com.martin.ruby.chat.ChatUi
import com.martin.ruby.voice.VoiceUi
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * What Ruby's orb shows. Styled after the "Aurora Orb" from voiceorbs.vercel.app (a React web
 * component), rebuilt natively: the state blending, energy curves and timings here follow its
 * orb-state.ts and use-orb-animator.ts. Kept free of Android, for tests.
 */
enum class OrbState(val label: String) {
    IDLE("Ready"),
    CONNECTING("Getting her voice ready"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    ERROR("Something went wrong"),
}

/** Which state the orb shows, from the chat and the voice. */
fun orbStateFor(chat: ChatUi, voice: VoiceUi): OrbState = when {
    voice.listening -> OrbState.LISTENING
    // Speaking only once she can be heard; before that the first words are still being made.
    voice.speaking && voice.sounding -> OrbState.SPEAKING
    voice.speaking && voice.loading -> OrbState.CONNECTING
    voice.speaking -> OrbState.THINKING
    // A reply arriving as text only: the words appearing are her "speaking".
    chat.sending && !chat.replying.isNullOrBlank() && !voice.readingReply -> OrbState.SPEAKING
    chat.sending -> OrbState.THINKING
    chat.error != null || voice.problem != null -> OrbState.ERROR
    else -> OrbState.IDLE
}

/** The orb's motion maths, as in the reference. */
object OrbMath {

    /** How fast states blend in: active ones in about 0.2 s, idle settles in about 0.6 s. */
    const val ENTER_RATE = 14f
    const val SETTLE_RATE = 5f
    const val ERROR_RATE = 10f

    /** How fast the level follows: quick to rise, slower to fall. */
    const val LEVEL_ATTACK_RATE = 14f
    const val LEVEL_RELEASE_RATE = 4f

    /** Longest step for one frame, so a stalled frame doesn't jump. */
    const val MAX_FRAME_DT = 0.1f

    fun rate(state: OrbState) = when (state) {
        OrbState.IDLE -> SETTLE_RATE
        OrbState.ERROR -> ERROR_RATE
        else -> ENTER_RATE
    }

    /** Exponential easing of [current] toward [target]. */
    fun approach(current: Float, target: Float, rate: Float, dt: Float): Float =
        current + (target - current) * (1f - exp(-rate * dt))

    fun smoothLevel(current: Float, target: Float, dt: Float): Float =
        approach(current, target, if (target > current) LEVEL_ATTACK_RATE else LEVEL_RELEASE_RATE, dt)

    private fun wave(x: Double) = (0.5 - 0.5 * cos(x)).toFloat()

    /** Each state's made-up "audio level" when there's no live one, at time [t] (seconds). */
    fun energy(state: OrbState, t: Double): Float = when (state) {
        OrbState.LISTENING -> 0.4f + 0.32f * wave(t * 17) + 0.18f * wave(t * 8.2 + 3)
        OrbState.SPEAKING -> 0.3f + 0.24f * wave(t * 12.4) + 0.16f * wave(t * 6 + 1.2)
        OrbState.THINKING -> 0.24f + 0.2f * wave(t * 4.8)
        OrbState.CONNECTING -> 0.12f + 0.1f * wave(t * 3.2)
        OrbState.ERROR -> 0.15f + 0.5f * errorPulse(t)
        OrbState.IDLE -> 0f
    }

    /** How long one error "double pulse" cycle takes, in seconds. */
    const val ERROR_CYCLE = 1.6

    /**
     * The error state's own motion: two quick pulses, then a rest, over and over (0..1). A
     * rhythm no other state has, so an error is recognizable without relying on its color.
     */
    fun errorPulse(t: Double): Float {
        val x = t - kotlin.math.floor(t / ERROR_CYCLE) * ERROR_CYCLE
        fun bump(center: Double) = exp(-((x - center) / 0.05) * ((x - center) / 0.05)).toFloat()
        return (bump(0.08) + bump(0.30)).coerceAtMost(1f)
    }

    /**
     * A looping keyframe animation at [progress] (0..1): [values] at [stops], eased in and out
     * between them like CSS's ease-in-out.
     */
    fun keyframes(progress: Float, stops: FloatArray, values: FloatArray): Float {
        val p = progress - kotlin.math.floor(progress)
        var i = 0
        while (i < stops.size - 2 && p > stops[i + 1]) i++
        val span = stops[i + 1] - stops[i]
        val u = if (span <= 0f) 0f else ((p - stops[i]) / span).coerceIn(0f, 1f)
        val eased = u * u * (3f - 2f * u)
        return values[i] + (values[i + 1] - values[i]) * eased
    }

    fun clamp01(v: Float) = v.coerceIn(0f, 1f)
}

/** How much each state shows right now (they cross-fade); always adds up to 1. */
class StateMix(initial: OrbState = OrbState.IDLE) {

    private val weights = FloatArray(OrbState.entries.size).also { it[initial.ordinal] = 1f }

    fun weight(state: OrbState) = weights[state.ordinal]

    fun update(state: OrbState, dt: Float) {
        val rate = OrbMath.rate(state)
        var total = 0f
        for (s in OrbState.entries) {
            val target = if (s == state) 1f else 0f
            val next = OrbMath.approach(weights[s.ordinal], target, rate, dt)
            weights[s.ordinal] = if (target == 0f && next < 0.001f) 0f else next
            total += weights[s.ordinal]
        }
        if (total > 0f) for (i in weights.indices) weights[i] /= total
    }

    fun snap(state: OrbState) {
        weights.fill(0f)
        weights[state.ordinal] = 1f
    }

    fun settled(state: OrbState) = weight(state) > 0.999f

    /** The states' made-up levels, mixed by weight. */
    fun energy(t: Double): Float {
        var e = 0f
        for (s in OrbState.entries) if (weights[s.ordinal] > 0f) e += weights[s.ordinal] * OrbMath.energy(s, t)
        return e
    }
}

/**
 * The orb's animation, one frame at a time: state blend, clock and level. The level is the
 * live audio level when there is one (0..1), else each state's made-up one.
 */
class OrbAnimator(initial: OrbState = OrbState.IDLE) {

    val mix = StateMix(initial)

    /** Animation time in seconds (times the speed). */
    var time = 0.0
        private set

    var level = 0f
        private set

    private var state = initial

    fun step(state: OrbState, liveLevel: Float, dt: Float, speed: Float = 1f) {
        val step = dt.coerceIn(0f, OrbMath.MAX_FRAME_DT)
        this.state = state
        mix.update(state, step)
        time += step * speed.coerceAtLeast(0f)
        val target = if (liveLevel >= 0f) liveLevel.coerceAtMost(1f) else mix.energy(time)
        level = OrbMath.smoothLevel(level, target, step)
    }

    /** Jumps straight to [state], for when animations are turned off. */
    fun snap(state: OrbState) {
        this.state = state
        mix.snap(state)
        level = 0f
    }

    /** Slow-moving states, which look the same at a lower frame rate (saves battery). */
    val calm: Boolean
        get() = state in CALM_STATES && mix.settled(state)

    /** Low, middle and high "bands" of the level, as in the reference. */
    val bass get() = OrbMath.clamp01(level * (0.78f + 0.22f * sin(time * 2.3).toFloat()))
    val treble get() = OrbMath.clamp01(level * (0.78f + 0.22f * sin(time * 4.6 + 4.2).toFloat()))

    private companion object {
        // Not error: its quick double pulse needs every frame to look crisp.
        val CALM_STATES = setOf(OrbState.IDLE, OrbState.CONNECTING)
    }
}

/**
 * The orb's color themes. Each has its own normal and thinking colors; the error state is
 * amber on every theme ([AuroraPalette.error]), so it never blends in.
 */
enum class OrbTheme(val label: String, val explained: String) {
    AURORA("Aurora", "Cyan and violet northern lights, as in the original design."),
    RUBY("Ruby", "Ruby red and rose, on a deep wine-colored night sky.");

    fun normal(): AuroraPalette = when (this) {
        AURORA -> AuroraPalette.normal()
        RUBY -> AuroraPalette.rubyNormal()
    }

    fun thinking(): AuroraPalette = when (this) {
        AURORA -> AuroraPalette.thinking()
        RUBY -> AuroraPalette.rubyThinking()
    }
}

/** The orb's colors for one look (normal, thinking, error), as ARGB ints. */
data class AuroraPalette(
    val skyHi: Int,
    val skyTop: Int,
    val skyMid: Int,
    val skyLow: Int,
    val hi: Int,
    val green: Int,
    val cyan: Int,
    val pink: Int,
    val glow: Int,
) {
    companion object {
        const val FROM = 0xFF22D3EE.toInt() // cyan
        const val TO = 0xFFA855F7.toInt() // violet

        private val NIGHT = intArrayOf(0xFF16223C.toInt(), 0xFF0D1526.toInt(), 0xFF0A111F.toInt(), 0xFF04070E.toInt())

        fun normal(from: Int = FROM, to: Int = TO): AuroraPalette {
            val green = mix(from, 0xFF34D399.toInt(), 0.55f)
            val cyan = mix(from, to, 0.38f)
            return AuroraPalette(
                NIGHT[0], NIGHT[1], NIGHT[2], NIGHT[3],
                hi = mix(from, 0xFFECFDF5.toInt(), 0.62f),
                green = green,
                cyan = cyan,
                pink = mix(to, 0xFFF472B6.toInt(), 0.42f),
                glow = mix(green, cyan, 0.40f),
            )
        }

        /** Thinking leans toward the second color. */
        fun thinking(from: Int = FROM, to: Int = TO) = normal(from, to).copy(
            green = mix(to, from, 0.32f),
            pink = mix(to, 0xFFF472B6.toInt(), 0.45f),
            glow = mix(to, from, 0.25f),
        )

        /** Ruby: a ruby red. */
        const val RUBY = 0xFFE0115F.toInt()

        /** Ruby's darker partner, a plum that thinking leans toward. */
        const val PLUM = 0xFF7E1F86.toInt()

        /** The Ruby theme: ruby-red and rose light, with a raspberry accent, on a wine night. */
        fun rubyNormal(): AuroraPalette {
            val main = RUBY
            val rose = 0xFFFF4D6D.toInt()
            return AuroraPalette(
                0xFF2A0C17.toInt(), 0xFF1B0810.toInt(), 0xFF13050B.toInt(), 0xFF070204.toInt(),
                hi = 0xFFFFE4EC.toInt(),
                green = main,
                cyan = rose,
                pink = 0xFFC2185B.toInt(), // raspberry
                glow = mix(main, rose, 0.40f),
            )
        }

        /** Ruby while thinking: leans toward plum, as Aurora's leans toward violet. */
        fun rubyThinking() = rubyNormal().copy(
            green = mix(RUBY, PLUM, 0.45f),
            pink = PLUM,
            glow = mix(RUBY, PLUM, 0.35f),
        )

        /** The error color: amber, so it never clashes with a red orb (such as the ruby-red option). */
        const val AMBER = 0xFFFF9F1C.toInt()

        /** The error color for text on a light background, where bright amber is hard to read. */
        const val AMBER_TEXT_ON_LIGHT = 0xFFB45309.toInt()

        /**
         * Error is amber and orange on a dark warm sky. The same whatever the orb's own colors,
         * so it looks like an error on every color theme.
         */
        fun error() = AuroraPalette(
            0xFF2A1D0B.toInt(), 0xFF1D1408.toInt(), 0xFF150E05.toInt(), 0xFF080502.toInt(),
            hi = 0xFFFFE8C2.toInt(),
            green = AMBER,
            cyan = 0xFFF77F00.toInt(), // deeper orange
            pink = 0xFFFFC36B.toInt(), // light amber
            glow = AMBER,
        )

        /** Mixes opaque colors [a] and [b] (t = 0 is a, 1 is b) in linear light. */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun lin(c: Int) = (c / 255.0).let { v -> if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
            fun srgb(v: Double) = (if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055)
                .let { (it * 255).coerceIn(0.0, 255.0).toInt() }
            fun channel(shift: Int): Int {
                val x = lin((a shr shift) and 0xFF)
                val y = lin((b shr shift) and 0xFF)
                return srgb(x + (y - x) * t)
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
