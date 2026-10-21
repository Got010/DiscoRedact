package app.discoredact.work

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.discoredact.Graph
import app.discoredact.MainActivity
import app.discoredact.core.executor.DryRunTransport
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.Sleeper
import app.discoredact.core.executor.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Runs sessions of one run as a `dataSync` foreground worker. One worker, one request in flight (the executor, the
 * transport's lock and the unique work name all enforce it). The worker keeps its foreground service through the
 * breaks between sessions and ends only for quiet hours, the daily cap, a halt, a pause, a stop or the end of the
 * run; a kill or reboot leaves the per-message rows as the truth and the run resumable.
 */
class DeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph = Graph.get(applicationContext)
    private val runId = inputData.getLong(KEY_RUN_ID, -1L)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing")

    override suspend fun doWork(): Result {
        if (runId <= 0) return Result.failure()
        val options = withContext(Dispatchers.IO) { graph.repository.runOptions(runId) } ?: return Result.failure()
        try {
            setForeground(foregroundInfo(if (options.dryRun) "Dry run" else "Deleting"))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // Android 12+ refuses a foreground service started from the background (a delayed enqueue firing while
            // the app is closed). Nothing is sent; the user opens the app, which resumes the run in the foreground.
            withContext(Dispatchers.IO) { graph.repository.markWaitingForApp(runId) }
            notifyOpenApp()
            return Result.success()
        }
        val config = graph.settings.settings.value.pacing.toConfig()
        val store = graph.runStore
        val transport = if (options.dryRun) DryRunTransport else graph.transport
        val executor = Executor(store, transport, graph.credentials, config, SystemClock(), Sleeper { ms -> delay(ms) }, Random.Default)
        while (true) {
            val end = withContext(Dispatchers.IO) { executor.runSession(runId, options.dryRun, options.oldestFirst) }
            when (end) {
                SessionEnd.Done -> {
                    if (options.dryRun) graph.settings.update { it.copy(dryRunCompleted = true, dryRun = false) }
                    return Result.success()
                }
                is SessionEnd.Break -> {
                    // The break is waited here, foreground service kept: a fresh session could not start from the
                    // background. The daily cap keeps the day's running time far under the dataSync budget.
                    setForeground(foregroundInfo("Break", "Next session in about ${end.breakMs / 60_000} minutes"))
                    delay(end.breakMs)
                }
                is SessionEnd.QuietUntil -> {
                    withContext(Dispatchers.IO) { graph.repository.markWaiting(runId, Executor.STOP_WAITING_QUIET, end.untilMs) }
                    RunScheduler(applicationContext).enqueueAt(runId, end.untilMs)
                    return Result.success()
                }
                is SessionEnd.CapUntil -> {
                    withContext(Dispatchers.IO) { graph.repository.markWaiting(runId, Executor.STOP_WAITING_CAP, end.untilMs) }
                    RunScheduler(applicationContext).enqueueAt(runId, end.untilMs)
                    return Result.success()
                }
                is SessionEnd.Halted, SessionEnd.PausedTransient, SessionEnd.Stopped, SessionEnd.NoCredentials -> return Result.success()
            }
        }
    }

    private fun channel(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Runs", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress of a run" })
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundInfo(title: String, text: String? = null): ForegroundInfo {
        channel(applicationContext.getSystemService(NotificationManager::class.java))
        // Counts only, never a message's text, never a header (CLAUDE.md rule 9).
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DiscoRedact: $title")
            .setContentText(text ?: "Run #$runId")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyOpenApp() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        channel(nm)
        val n = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DiscoRedact: open the app to continue")
            .setContentText("Run #$runId is waiting; a run can only continue while the app is open.")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    companion object {
        const val KEY_RUN_ID = "run_id"
        const val CHANNEL = "runs"
        const val NOTIFICATION_ID = 1001
    }
}
