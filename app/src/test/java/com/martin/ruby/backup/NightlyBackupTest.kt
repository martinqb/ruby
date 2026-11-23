package com.martin.ruby.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class NightlyBackupTest {

    private val zone = ZoneId.of("America/Chicago")
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @Test
    fun evening_waitsUntil2amTomorrow() {
        assertEquals(Duration.ofHours(4).plusMinutes(30), NightlyBackup.delayUntil(2, at(22, 21, 30)))
    }

    @Test
    fun earlyMorning_waitsUntil2amToday() {
        assertEquals(Duration.ofMinutes(45), NightlyBackup.delayUntil(2, at(22, 1, 15)))
    }

    @Test
    fun exactly2am_waitsAWholeDay() {
        assertEquals(Duration.ofDays(1), NightlyBackup.delayUntil(2, at(22, 2, 0)))
    }
}
