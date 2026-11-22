package com.martin.ruby.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveRetentionTest {

    private fun file(day: Int) = DriveFile("id$day", "day$day", 1_000, day * 86_400_000L)

    @Test
    fun keepsTheNewestSeven() {
        val files = (1..10).map(::file).shuffled()
        assertEquals(listOf("id3", "id2", "id1"), DriveRetention.toDelete(files).map { it.id })
    }

    @Test
    fun sevenOrFewer_deletesNothing() {
        assertEquals(emptyList<DriveFile>(), DriveRetention.toDelete((1..7).map(::file)))
        assertEquals(emptyList<DriveFile>(), DriveRetention.toDelete(emptyList()))
    }

    @Test
    fun neverDeletesTheNewest_evenIfAskedToKeepNone() {
        assertEquals(listOf("id1"), DriveRetention.toDelete(listOf(file(1), file(2)), keep = 0).map { it.id })
    }
}
