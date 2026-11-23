package com.martin.ruby.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One backup attempt, for the history on the Backup screen. */
data class BackupRecord(
    /** When it finished (wall-clock time). */
    val timeMillis: Long,
    val kind: Kind,
    val succeeded: Boolean,
    /** On success, what was backed up ("1,234 messages, 2.1 MB"); on failure, why it failed. */
    val detail: String,
) {
    enum class Kind(val label: String) {
        NIGHTLY("Nightly"),
        NIGHTLY_TEST("Nightly (test run)"),
        MANUAL_DRIVE("Manual, to Google Drive"),
        MANUAL_FILE("Manual, to a file"),
    }
}

/**
 * The last [KEEP] backups, newest first. Kept with Ruby's settings rather than in the
 * memory database: a restore replaces that database, and the history should still be
 * readable when memory can't be opened, which is when it's most useful.
 */
class BackupHistory(private val context: Context) {

    private val key = stringPreferencesKey("backup_history")

    val records: Flow<List<BackupRecord>> = context.dataStore.data.map { decode(it[key]) }

    suspend fun add(kind: BackupRecord.Kind, succeeded: Boolean, detail: String) {
        val record = BackupRecord(System.currentTimeMillis(), kind, succeeded, detail)
        context.dataStore.edit { prefs -> prefs[key] = encode(listOf(record) + decode(prefs[key])) }
    }

    suspend fun succeeded(kind: BackupRecord.Kind, messageCount: Int, sizeBytes: Long) =
        add(kind, true, "%,d messages, %s".format(messageCount, sizeText(sizeBytes)))

    suspend fun failed(kind: BackupRecord.Kind, why: String) = add(kind, false, why)

    companion object {
        const val KEEP = 30

        /**
         * One record per line: time, kind, 1 or 0, detail, separated by tabs. Tabs and line
         * breaks in the detail are replaced with spaces, so they can't break the format.
         * Lines that can't be read (e.g. an unknown kind from a newer version) are skipped.
         */
        fun encode(records: List<BackupRecord>): String = records.take(KEEP).joinToString("\n") { r ->
            listOf(r.timeMillis, r.kind.name, if (r.succeeded) 1 else 0, r.detail.replace(Regex("[\t\r\n]+"), " "))
                .joinToString("\t")
        }

        fun decode(text: String?): List<BackupRecord> = text.orEmpty().lines().mapNotNull { line ->
            val parts = line.split("\t", limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val kind = BackupRecord.Kind.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            BackupRecord(parts[0].toLongOrNull() ?: return@mapNotNull null, kind, parts[2] == "1", parts[3])
        }.take(KEEP)

        fun sizeText(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
