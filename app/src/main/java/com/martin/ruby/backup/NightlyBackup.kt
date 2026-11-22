package com.martin.ruby.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.martin.ruby.MainActivity
import com.martin.ruby.R
import com.martin.ruby.drive.DriveAuth
import com.martin.ruby.drive.DriveBackup
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * The nightly Google Drive backup. WorkManager runs it about once a day, only while the
 * phone is charging and on Wi-Fi (in practice: overnight on the charger), and keeps the
 * schedule across restarts and app updates.
 */
object NightlyBackup {

    private const val WORK_NAME = "nightly-drive-backup"
    private const val TEST_WORK_NAME = "nightly-drive-backup-test"
    private const val CHANNEL_ID = "backup"
    private const val APPROVAL_NOTIFICATION_ID = 2

    /** Aim for the first run at about this hour; after that it repeats every 24 hours. */
    const val TARGET_HOUR = 2

    /** Turns the nightly backup on (or keeps it, if it's already scheduled). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NightlyBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi, not mobile data
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInitialDelay(delayUntil(TARGET_HOUR, ZonedDateTime.now()).toMinutes(), TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Runs the same job once, now, ignoring charging and Wi-Fi. For testing. */
    fun runTestNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<NightlyBackupWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(TEST_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** How long from [now] until the next [hour]:00. Pure, so it's unit tested. */
    fun delayUntil(hour: Int, now: ZonedDateTime): Duration {
        var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    // The "approve again" notification

    fun showApprovalNotice(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return // the Backup screen shows it too
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Backups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a backup needs your attention."
            }
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_BACKUP, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Backups paused")
            .setContentText("Google needs you to approve Drive access again. Tap to approve.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Google needs you to approve Ruby's access to your Google Drive again before " +
                        "nightly backups can continue. Tap, then tap Approve."
                )
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(APPROVAL_NOTIFICATION_ID, notification)
    }

    fun clearApprovalNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(APPROVAL_NOTIFICATION_ID)
    }
}

/** One nightly run: back up to Drive, and record how it went. */
class NightlyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val status = BackupStatusStore(applicationContext)
        val manager = BackupManager(applicationContext)
        if (!manager.isSetUp()) {
            status.recordFailure("Backup isn't set up on this phone (no passphrase chosen).")
            return Result.success()
        }
        return try {
            val token = DriveAuth.silentToken(applicationContext)
            DriveBackup(applicationContext, manager).backUp(token) // records the success
            NightlyBackup.clearApprovalNotice(applicationContext)
            Result.success()
        } catch (e: DriveAuth.NeedsApproval) {
            // Trying again won't help until the user approves, so wait for them.
            status.recordNeedsApproval()
            SensingLog.add("Nightly backup paused: Google Drive access needs approving again.")
            NightlyBackup.showApprovalNotice(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e // WorkManager stopped us (e.g. unplugged); it'll run again later
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            status.recordFailure(why)
            SensingLog.add("Nightly backup failed (try ${runAttemptCount + 1} of $MAX_TRIES): $why")
            if (runAttemptCount + 1 < MAX_TRIES) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_TRIES = 3
    }
}
