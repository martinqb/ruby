package com.martin.ruby.drive

/** A backup file in the app's private folder on the user's Google Drive. */
data class DriveFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    /** When it was last changed (wall-clock time). */
    val modifiedMillis: Long,
)

/** How many backups to keep in Drive. Pure, so it's unit tested. */
object DriveRetention {

    /** A week of nightly backups. */
    const val KEEP = 7

    /** The backups to delete: everything but the newest [keep]. Never the newest one. */
    fun toDelete(files: List<DriveFile>, keep: Int = KEEP): List<DriveFile> =
        files.sortedByDescending { it.modifiedMillis }.drop(maxOf(1, keep))
}
