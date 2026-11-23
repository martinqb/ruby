package com.martin.ruby.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageBudgetTest {

    private val gb = 1_000_000_000L
    private val total = 256 * gb

    @Test
    fun default_isFivePercentOfThePhone() {
        assertEquals(12_800_000_000L, StorageBudget.chosen(BudgetSetting(), total))
        assertEquals(12_800_000_000L, StorageBudget.limit(BudgetSetting(), total, freeBytes = 100 * gb, usedBytes = 0))
    }

    @Test
    fun percent_isKeptInRange() {
        assertEquals(total / 100, StorageBudget.chosen(BudgetSetting(percent = 0.1), total))
        assertEquals(total / 2, StorageBudget.chosen(BudgetSetting(percent = 90.0), total))
    }

    @Test
    fun fixedAmount_isUsedAsIs() {
        val setting = BudgetSetting(mode = BudgetSetting.Mode.FIXED, fixedBytes = 2 * gb)
        assertEquals(2 * gb, StorageBudget.limit(setting, total, freeBytes = 100 * gb, usedBytes = gb))
    }

    @Test
    fun nearlyFullPhone_shrinksTheLimit_toLeaveTheReserveFree() {
        // 30 GB free, 25.6 GB reserve: only 4.4 GB more for memory, on top of the 1 GB it has.
        val limit = StorageBudget.limit(BudgetSetting(), total, freeBytes = 30 * gb, usedBytes = gb)
        assertEquals(gb + 30 * gb - 25_600_000_000L, limit)
        // Full phone: the limit is what memory already uses, so it's at the limit and makes room.
        assertEquals(gb, StorageBudget.limit(BudgetSetting(), total, freeBytes = 5 * gb, usedBytes = gb))
        assertEquals(StorageBudget.Level.FULL, StorageBudget.level(gb, gb))
    }

    @Test
    fun reserve_isATenth_atLeastAGigabyte() {
        assertEquals(25_600_000_000L, StorageBudget.reserve(total))
        assertEquals(gb, StorageBudget.reserve(4 * gb))
    }

    @Test
    fun tinyTest_overridesEverything() {
        val setting = BudgetSetting(mode = BudgetSetting.Mode.FIXED, fixedBytes = 5 * gb, tinyTest = true)
        assertEquals(StorageBudget.TINY_TEST_BYTES, StorageBudget.limit(setting, total, freeBytes = 0, usedBytes = 0))
    }

    @Test
    fun levels_warnAt80_fullAt100() {
        assertEquals(StorageBudget.Level.OK, StorageBudget.level(79, 100))
        assertEquals(StorageBudget.Level.WARN, StorageBudget.level(80, 100))
        assertEquals(StorageBudget.Level.WARN, StorageBudget.level(99, 100))
        assertEquals(StorageBudget.Level.FULL, StorageBudget.level(100, 100))
        assertEquals(StorageBudget.Level.FULL, StorageBudget.level(0, 0))
    }

    @Test
    fun toFree_getsBackTo70Percent() {
        assertEquals(30, StorageBudget.toFree(100, 100))
        assertEquals(0, StorageBudget.toFree(60, 100))
    }

    @Test
    fun parseAmount() {
        assertEquals(2_500_000_000L, StorageBudget.parseAmount("2.5", "GB"))
        assertEquals(2_500_000_000L, StorageBudget.parseAmount(" 2,5 ", "GB"))
        assertEquals(1_000_000_000_000L, StorageBudget.parseAmount("1", "TB"))
        assertNull(StorageBudget.parseAmount("lots", "GB"))
        assertNull(StorageBudget.parseAmount("-1", "GB"))
        assertNull(StorageBudget.parseAmount("0", "GB"))
    }

    @Test
    fun describe() {
        assertEquals("512 bytes", StorageBudget.describe(512))
        assertEquals("256 KB", StorageBudget.describe(256_000))
        assertEquals("12.8 GB", StorageBudget.describe(12_800_000_000L))
        assertEquals("1.0 TB", StorageBudget.describe(1_000_000_000_000L))
    }
}
