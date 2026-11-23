package com.martin.ruby.drive

/** A file in the app's private folder on the user's Google Drive. */
data class DriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    /** When it was last changed (wall-clock time). */
    val modifiedMillis: Long,
) {
    /**
     * A full backup, as opposed to older memories the storage limit moved there
     * ([DriveBackup.ARCHIVE_PREFIX]). Only backups are listed for restoring or cleaned up.
     */
    val isBackup: Boolean get() = name.startsWith("ruby-memory-") && name.endsWith(".rubybackup")
}

/** How many backups to keep in Drive. Pure, so it's unit tested. */
object DriveRetention {

    /** A week of nightly backups. */
    const val KEEP = 7

    /**
     * The backups to delete: everything but the newest [keep]. Never the newest one, and
     * never anything that isn't a backup (memories moved to Drive must never be cleaned up).
     */
    fun toDelete(files: List<DriveFile>, keep: Int = KEEP): List<DriveFile> =
        files.filter { it.isBackup }.sortedByDescending { it.modifiedMillis }.drop(maxOf(1, keep))
}
