package com.martin.ruby.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * How loud each slice of audio given to the speaker is, by its place in the stream, so the
 * loudness of what's coming out right now can be looked up. The speaker holds up to half a
 * second of audio, so the slice just written isn't the one being heard; looking it up by the
 * play position keeps the orb in time with her voice.
 *
 * Written by the player thread, read by the screen; both are quick.
 */
class LevelTrack(private val capacity: Int = 64) {

    private val starts = LongArray(capacity)
    private val ends = LongArray(capacity)
    private val levels = FloatArray(capacity)
    private var count = 0

    /** Records that frames [start] until [end] have loudness [level] (0..1). */
    @Synchronized
    fun add(start: Long, end: Long, level: Float) {
        val i = count % capacity
        starts[i] = start
        ends[i] = end
        levels[i] = level
        count++
    }

    /** The loudness at [frame], or 0 if nothing playing there is known (a pause between pieces). */
    @Synchronized
    fun at(frame: Long): Float {
        for (n in 0 until minOf(count, capacity)) {
            val i = (count - 1 - n) % capacity
            if (frame >= starts[i] && frame < ends[i]) return levels[i]
        }
        return 0f
    }

    @Synchronized
    fun clear() {
        count = 0
    }

    companion object {
        /** Quieter than this (dB below full scale) counts as silence. */
        const val FLOOR_DB = -45f

        /** dB above the floor that count as full loudness. */
        const val RANGE_DB = 30f

        /** Loudness 0..1 of [length] samples from [offset], from their RMS in decibels. */
        fun levelOf(samples: FloatArray, offset: Int, length: Int): Float {
            if (length <= 0) return 0f
            var sum = 0.0
            for (i in offset until offset + length) sum += samples[i] * samples[i]
            val rms = sqrt(sum / length)
            if (rms <= 1e-6) return 0f
            val db = 20f * log10(rms).toFloat()
            return ((db - FLOOR_DB) / RANGE_DB).coerceIn(0f, 1f)
        }
    }
}
