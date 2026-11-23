package com.martin.ruby.memory

import com.martin.ruby.backup.BackupCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.Writer
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

/**
 * Writes everything in memory to a file: the messages in the database, the compressed
 * bundles on the phone and, if asked, the bundles moved to Google Drive. Nothing in memory
 * changes: bundles are opened just for the export, and Drive's stay in Drive.
 *
 * Works on any [MemoryDatabase], so the emulator test runs it on its own file.
 */
class MemoryExporter(private val db: MemoryDatabase) {

    class Options(
        val format: MemoryExport.Format,
        val includeDrive: Boolean,
        val car: MemoryExport.Car? = null,
        val places: List<MemoryExport.Place> = emptyList(),
        val locale: Locale = Locale.getDefault(),
        val zone: TimeZone = TimeZone.getDefault(),
    )

    /**
     * Writes the export to [writer]. [cloud] is only asked for if there are bundles in Drive
     * and [Options.includeDrive] is on; if it throws, those bundles are left out and the
     * summary says so. [onProgress] gets the number of messages written so far.
     */
    suspend fun export(
        writer: Writer,
        options: Options,
        cloud: suspend () -> ArchiveCloud,
        onProgress: (Int) -> Unit = {},
    ): MemoryExport.Summary = MemoryArchiver.whileArchivesStill {
        withContext(Dispatchers.IO) {
            val output = MemoryExport.output(options.format, writer, options.locale, options.zone)
            output.begin(System.currentTimeMillis(), options.car, options.places)

            val skipped = mutableListOf<String>()
            var drive: ArchiveCloud? = null
            var driveProblem: String? = null
            suspend fun drive(): ArchiveCloud? {
                if (drive == null && driveProblem == null) {
                    try {
                        drive = cloud()
                    } catch (e: CloudUnavailable) {
                        driveProblem = e.message ?: "Google Drive isn't available."
                    } catch (e: IOException) {
                        driveProblem = "Couldn't reach Google Drive: ${e.message}"
                    }
                }
                return drive
            }

            val bundles = mutableListOf<MemoryExport.Bundle>()
            var driveLeftOut = 0
            for (summary in db.archiveDao().storedNow()) {
                val range = { MemoryExport.describeRange(summary.messageCount, summary.firstMillis, summary.lastMillis, options.locale, options.zone) }
                when (summary.state) {
                    MemoryArchive.STATE_PHONE -> bundles += MemoryExport.Bundle(summary.firstMillis, MemoryExport.Source.PHONE_ARCHIVE) {
                        try {
                            val data = db.archiveDao().get(summary.id)?.data ?: throw IOException("it's empty")
                            MemoryPack.unpack(data)
                        } catch (e: IOException) {
                            skipped += "${range()}, compressed on this phone: couldn't be opened (${e.message})."
                            null
                        }
                    }
                    MemoryArchive.STATE_DRIVE ->
                        if (!options.includeDrive) driveLeftOut += summary.messageCount
                        else bundles += MemoryExport.Bundle(summary.firstMillis, MemoryExport.Source.DRIVE_ARCHIVE) {
                            fromDrive(summary.id, drive(), driveProblem, range(), skipped)
                        }
                }
            }
            if (driveLeftOut > 0) {
                skipped += "${if (driveLeftOut == 1) "1 message" else "$driveLeftOut messages"} moved to Google Drive " +
                    "(you chose not to include them)."
            }

            val counts = IntArray(MemoryExport.Source.entries.size)
            var written = 0
            MemoryExport.merge(
                nextPage = { after ->
                    coroutineContext.ensureActive()
                    db.dao().pageAfter(after?.timeMillis ?: Long.MIN_VALUE, after?.id ?: Long.MIN_VALUE, PAGE_SIZE)
                },
                bundles = bundles,
            ) { item ->
                output.message(item)
                counts[item.source.ordinal]++
                if (++written % 100 == 0) onProgress(written)
            }
            val summary = MemoryExport.Summary(
                fromMemory = counts[MemoryExport.Source.MEMORY.ordinal],
                fromPhoneArchive = counts[MemoryExport.Source.PHONE_ARCHIVE.ordinal],
                fromDrive = counts[MemoryExport.Source.DRIVE_ARCHIVE.ordinal],
                skipped = skipped,
            )
            output.end(summary)
            writer.flush()
            onProgress(written)
            summary
        }
    }

    /** Downloads and opens one Drive bundle, or notes why it couldn't be. Drive's copy stays. */
    private suspend fun fromDrive(
        id: Long,
        drive: ArchiveCloud?,
        driveProblem: String?,
        range: String,
        skipped: MutableList<String>,
    ): List<MemoryMessage>? {
        if (drive == null) {
            skipped += "$range, in Google Drive: $driveProblem"
            return null
        }
        return try {
            val archive = db.archiveDao().get(id) ?: throw IOException("it no longer exists")
            val fileId = archive.driveFileId ?: throw IOException("it has no Drive file")
            val sealed = drive.download(fileId)
            if (archive.driveSha256 != null && MemoryArchiver.sha256(sealed) != archive.driveSha256) {
                throw IOException("Google Drive's copy is damaged")
            }
            val messages = MemoryPack.unpack(drive.open(sealed, null))
            if (messages.size != archive.messageCount) throw IOException("it's incomplete")
            messages
        } catch (e: BackupCrypto.CantOpen) {
            skipped += "$range, in Google Drive: locked with an earlier passphrase. Bring it back on the Storage screen first."
            null
        } catch (e: IOException) {
            skipped += "$range, in Google Drive: couldn't be downloaded (${e.message})."
            null
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
