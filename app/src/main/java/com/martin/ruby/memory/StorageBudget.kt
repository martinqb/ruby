package com.martin.ruby.memory

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** How big Ruby's memory may grow, as the user set it. */
data class BudgetSetting(
    val mode: Mode = Mode.PERCENT,
    /** For [Mode.PERCENT]: percent of the phone's total storage. */
    val percent: Double = StorageBudget.DEFAULT_PERCENT,
    /** For [Mode.FIXED]: a fixed number of bytes. */
    val fixedBytes: Long = 0,
    /** Use [StorageBudget.TINY_TEST_BYTES] instead, to test the limit without filling the phone. */
    val tinyTest: Boolean = false,
) {
    enum class Mode { PERCENT, FIXED }
}

/**
 * The storage limit's arithmetic. Pure, so it's unit tested.
 *
 * Sizes use the same units as Android's storage settings: 1 GB = 1,000,000,000 bytes.
 */
object StorageBudget {

    const val DEFAULT_PERCENT = 5.0
    const val MIN_PERCENT = 1.0
    const val MAX_PERCENT = 50.0
    const val MIN_FIXED_BYTES = 100_000_000L // 0.1 GB

    /** Warn at this share of the budget. */
    const val WARN_AT = 0.80
    /** At the limit, pack and move memories until usage is back down to this share. */
    const val TARGET = 0.70

    /** The tiny test limit. An empty memory database takes about 41 KB; a test note about 2 KB. */
    const val TINY_TEST_BYTES = 300_000L

    /** Space Ruby always leaves free for the phone: a tenth of it, at least 1 GB. */
    fun reserve(totalBytes: Long): Long = max(totalBytes / 10, min(1_000_000_000L, totalBytes / 2))

    /** What the user asked for, before checking the phone has room. */
    fun chosen(setting: BudgetSetting, totalBytes: Long): Long = when {
        setting.tinyTest -> TINY_TEST_BYTES
        setting.mode == BudgetSetting.Mode.FIXED -> setting.fixedBytes
        else -> (totalBytes * setting.percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100).toLong()
    }

    /**
     * The limit in force: what the user chose, but never so much that memory would eat
     * into the [reserve]. If the phone fills up with other things, the limit shrinks, so
     * Ruby makes room by packing and moving her own memories, never the other way round.
     */
    fun limit(setting: BudgetSetting, totalBytes: Long, freeBytes: Long, usedBytes: Long): Long {
        val chosen = chosen(setting, totalBytes)
        if (setting.tinyTest) return chosen
        val roomOnPhone = usedBytes + max(0L, freeBytes - reserve(totalBytes))
        return min(chosen, roomOnPhone)
    }

    enum class Level { OK, WARN, FULL }

    fun level(usedBytes: Long, limitBytes: Long): Level = when {
        limitBytes <= 0 || usedBytes >= limitBytes -> Level.FULL
        usedBytes >= limitBytes * WARN_AT -> Level.WARN
        else -> Level.OK
    }

    /** How many bytes must go to get back down to [TARGET]. Zero if under it. */
    fun toFree(usedBytes: Long, limitBytes: Long): Long = max(0L, usedBytes - (limitBytes * TARGET).toLong())

    /** "12.5 GB", "180 KB". */
    fun describe(bytes: Long): String {
        val units = listOf("bytes" to 1L, "KB" to 1_000L, "MB" to 1_000_000L, "GB" to 1_000_000_000L, "TB" to 1_000_000_000_000L)
        val (unit, size) = units.last { bytes >= it.second || it.second == 1L }
        if (size == 1L) return "$bytes bytes"
        val value = bytes.toDouble() / size
        return if (value >= 100) "%.0f %s".format(Locale.US, value, unit) else "%.1f %s".format(Locale.US, value, unit)
    }

    /** Reads "2.5" with a unit ("GB" or "TB") into bytes, or null if it isn't a number. */
    fun parseAmount(typed: String, unit: String): Long? {
        val number = typed.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (number <= 0 || number.isNaN() || number.isInfinite()) return null
        val size = when (unit) {
            "GB" -> 1_000_000_000.0
            "TB" -> 1_000_000_000_000.0
            else -> return null
        }
        return (number * size).toLong()
    }
}
