package com.martin.ruby.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupHistoryTest {

    private fun record(i: Int, ok: Boolean = true, detail: String = "$i messages, 1 KB") =
        BackupRecord(1_000L * i, BackupRecord.Kind.entries[i % BackupRecord.Kind.entries.size], ok, detail)

    @Test
    fun recordsSurviveSavingAndReading() {
        val records = listOf(record(3), record(2, ok = false, detail = "Couldn't reach Google Drive: timeout."), record(1))
        assertEquals(records, BackupHistory.decode(BackupHistory.encode(records)))
    }

    @Test
    fun onlyTheNewest30AreKept() {
        val records = (100 downTo 1).map { record(it) }
        val kept = BackupHistory.decode(BackupHistory.encode(records))
        assertEquals(BackupHistory.KEEP, kept.size)
        assertEquals(records.take(BackupHistory.KEEP), kept)
    }

    @Test
    fun tabsAndLineBreaksInAReason_cantBreakTheFormat() {
        val messy = record(1, ok = false, detail = "Drive said:\n\t500\r\nTry later")
        val back = BackupHistory.decode(BackupHistory.encode(listOf(messy, record(2))))
        assertEquals(2, back.size)
        assertEquals("Drive said: 500 Try later", back[0].detail)
    }

    @Test
    fun unreadableLines_areSkipped() {
        val text = BackupHistory.encode(listOf(record(1))) + "\n123\tFROM_THE_FUTURE\t1\tx\nnot a record"
        assertEquals(listOf(record(1)), BackupHistory.decode(text))
        assertEquals(emptyList<BackupRecord>(), BackupHistory.decode(null))
    }

    @Test
    fun sizes() {
        assertEquals("512 B", BackupHistory.sizeText(512))
        assertEquals("2 KB", BackupHistory.sizeText(2048))
        assertEquals("2.1 MB", BackupHistory.sizeText(2_202_009))
    }
}
