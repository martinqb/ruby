package com.martin.ruby.memory

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.martin.ruby.MainActivity
import com.martin.ruby.R
import com.martin.ruby.drive.DriveArchiveCloud
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Ruby's storage limit, on the phone: measures memory against the budget, warns at 80%,
 * and at the limit runs [MemoryArchiver] in the background.
 */
object MemoryLimit {

    /** Memory's size against its limit, right now. */
    data class Usage(
        val usedBytes: Long,
        val limitBytes: Long,
        /** What the setting asks for; more than [limitBytes] if the phone is short of space. */
        val chosenBytes: Long,
        val totalBytes: Long,
        val freeBytes: Long,
        val setting: BudgetSetting,
    ) {
        val level get() = StorageBudget.level(usedBytes, limitBytes)
        val fraction get() = if (limitBytes > 0) usedBytes.toDouble() / limitBytes else 1.0
        /** The phone is so full that the limit is lower than what the user chose. */
        val squeezed get() = limitBytes < chosenBytes
    }

    fun archiver(context: Context) = MemoryArchiver(
        MemoryDatabase.get(context.applicationContext),
        context.applicationContext.getDatabasePath(MemoryDatabase.FILE_NAME),
    )

    suspend fun usage(context: Context): Usage = withContext(Dispatchers.IO) {
        val setting = StorageSettings(context).currentBudget()
        val stat = StatFs(context.filesDir.path)
        val used = archiver(context).usageBytes()
        Usage(
            usedBytes = used,
            limitBytes = StorageBudget.limit(setting, stat.totalBytes, stat.availableBytes, used),
            chosenBytes = StorageBudget.chosen(setting, stat.totalBytes),
            totalBytes = stat.totalBytes,
            freeBytes = stat.availableBytes,
            setting = setting,
        )
    }

    /**
     * A quick look after memory grows (or the setting changes): warns once on passing 80%,
     * and starts the background job at the limit. Never throws.
     */
    suspend fun check(context: Context) {
        val usage = runCatching { usage(context) }.getOrElse { return }
        val settings = StorageSettings(context)
        when (usage.level) {
            StorageBudget.Level.OK -> settings.clearWarned()
            StorageBudget.Level.WARN -> if (settings.markWarned()) {
                val percent = (usage.fraction * 100).toInt()
                SensingLog.add("Memory is $percent% of its storage limit.")
                notify(
                    context,
                    "Memory is $percent% full",
                    "Ruby's memory is using ${StorageBudget.describe(usage.usedBytes)} of " +
                        "${StorageBudget.describe(usage.limitBytes)}. At the limit she'll compress older " +
                        "memories and move the oldest to your Google Drive backup. Nothing is deleted.",
                )
            }
            StorageBudget.Level.FULL -> runSoon(context)
        }
    }

    /** Runs the limit check in the background, now. Safe to call often. */
    fun runSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<MemoryLimitWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    const val WORK_NAME = "memory-limit"

    /** The background job's work: get under the limit if over it, and say what happened. */
    suspend fun enforce(context: Context): MemoryArchiver.Result? {
        val usage = usage(context)
        val archiver = archiver(context)
        archiver.cleanUpDrive { DriveArchiveCloud.connect(context) }
        if (usage.level != StorageBudget.Level.FULL) return null
        val result = archiver.enforce(usage.limitBytes) { DriveArchiveCloud.connect(context) }
        // Settling the write-ahead log can be enough on its own; then there was nothing to do.
        if (result.packedMessages == 0 && result.movedToDrive == 0 && result.problem == null) return result
        val settings = StorageSettings(context)
        val summary = buildString {
            append("Memory was at ${StorageBudget.describe(result.beforeBytes)}, now ${StorageBudget.describe(result.afterBytes)}")
            append(" (limit ${StorageBudget.describe(usage.limitBytes)}).")
            if (result.packedMessages > 0) append(" Compressed ${result.packedMessages} older messages.")
            if (result.movedToDrive > 0) {
                append(" Moved ${result.movedToDrive} compressed ${if (result.movedToDrive == 1) "bundle" else "bundles"} to Google Drive.")
            }
            result.problem?.let { append(" ").append(it) }
        }
        SensingLog.add(summary)
        settings.setNote(summary, problem = result.problem != null)
        if (result.problem != null) {
            notify(context, "Memory is full", result.problem)
        } else {
            settings.clearWarned()
            clearNotice(context)
        }
        return result
    }

    /** For the Storage screen's test button. Returns what happened, in words. */
    suspend fun moveOldestToDriveNow(context: Context): String = try {
        if (archiver(context).moveOldestToDrive { DriveArchiveCloud.connect(context) }) {
            "Moved the oldest compressed bundle to Google Drive, and checked Drive's copy.".also { SensingLog.add(it) }
        } else {
            "There's no compressed bundle on the phone to move yet."
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Couldn't move it: ${e.message ?: e.javaClass.simpleName}"
    }

    // Notifications

    private const val CHANNEL_ID = "memory_storage"
    private const val NOTIFICATION_ID = 3

    private fun notify(context: Context, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return // the Storage screen shows it too
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Memory storage", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when Ruby's memory is getting close to its storage limit."
            }
        )
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_STORAGE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun clearNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** Gets memory back under its limit in the background. */
class MemoryLimitWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val result = MemoryLimit.enforce(applicationContext)
        // Couldn't reach Drive (offline, say)? Try again later; WorkManager waits longer each time.
        if (result?.retryLater == true && runAttemptCount < 3) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val why = e.message ?: e.javaClass.simpleName
        SensingLog.add("Storage limit check failed: $why")
        StorageSettings(applicationContext).setNote("The storage check failed: $why", problem = true)
        Result.success()
    }
}
