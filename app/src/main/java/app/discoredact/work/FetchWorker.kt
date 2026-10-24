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
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.Sleeper
import app.discoredact.core.executor.SystemClock
import app.discoredact.core.history.FetchSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Runs sessions of the Fetch import as a `dataSync` foreground worker, the run worker's pattern: one worker, one request
 * in flight (the session, the transport's lock, the process-wide send mutex and worker mutex, and the unique work name
 * all enforce it), breaks waited inside the worker, quiet hours and the daily cap enqueueing the next session, and a
 * kill or reboot leaving the per-DM progress rows as the truth and the fetch resumable.
 */
class FetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val graph = Graph.get(applicationContext)

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Preparing")

    override suspend fun doWork(): Result {
        if (graph.fetchState.snapshot() == null) return Result.failure()
        // Held for the whole body: the run worker holds it too, and a wipe waits on it (see Graph.workerMutex).
        return graph.workerMutex.withLock { runSessions() }
    }

    private suspend fun runSessions(): Result {
        try {
            setForeground(foregroundInfo("Fetching"))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            // A delayed enqueue fired while the app was closed: nothing is read; the next open resumes the fetch.
            withContext(Dispatchers.IO) { graph.fetchState.paused(Executor.STOP_WAITING_APP, null) }
            notify("Open the app to continue", "The fetch is waiting; it continues only while the app is open.")
            return Result.success()
        }
        val config = graph.settings.settings.value.pacing.toConfig()
        val session = FetchSession(
            graph.fetchStore, graph.transport, graph.credentials, config, SystemClock(), Sleeper { ms -> delay(ms) }, Random.Default,
            sendMutex = graph.sendMutex,
        )
        val workerStart = System.currentTimeMillis()
        while (true) {
            when (val end = withContext(Dispatchers.IO) { session.runSession() }) {
                SessionEnd.Done -> {
                    val found = graph.fetchState.state.value?.found ?: 0
                    notify("Fetch finished", "Every listed DM was read; $found of your own messages found. Closed and left DMs are not in the list; the data package still finds them.")
                    return Result.success()
                }
                is SessionEnd.Break -> {
                    if (System.currentTimeMillis() - workerStart + end.breakMs > DeleteWorker.WORKER_BUDGET_MS) {
                        withContext(Dispatchers.IO) { graph.fetchState.paused(Executor.STOP_WAITING_APP, null) }
                        notify("Open the app to continue", "The fetch paused after ${DeleteWorker.WORKER_BUDGET_MS / 3_600_000} hours of background work; it continues on the next open.")
                        return Result.success()
                    }
                    setForeground(foregroundInfo("Break", "Next session in about ${end.breakMs / 60_000} minutes"))
                    delay(end.breakMs)
                    setForeground(foregroundInfo("Fetching"))
                }
                is SessionEnd.QuietUntil -> {
                    withContext(Dispatchers.IO) { graph.fetchState.paused(Executor.STOP_WAITING_QUIET, end.untilMs) }
                    FetchScheduler(applicationContext).enqueueAt(end.untilMs)
                    return Result.success()
                }
                is SessionEnd.CapUntil -> {
                    withContext(Dispatchers.IO) { graph.fetchState.paused(Executor.STOP_WAITING_CAP, end.untilMs) }
                    FetchScheduler(applicationContext).enqueueAt(end.untilMs)
                    return Result.success()
                }
                is SessionEnd.Halted -> {
                    // The run's wording: reason and status only, never a header, a body or the account id.
                    notify("Fetch halted", FetchSession.describe(end.reason.stopReason, null, workerStart))
                    return Result.success()
                }
                SessionEnd.PausedTransient -> {
                    notify("Fetch paused", "Repeated server or network errors; open the app to resume.")
                    return Result.success()
                }
                SessionEnd.NoCredentials -> {
                    notify("Fetch paused", "No credentials stored; nothing was read.")
                    return Result.success()
                }
                SessionEnd.NoAccountId -> {
                    notify("Fetch paused", FetchSession.describe(FetchSession.STOP_NO_ACCOUNT_ID, null, workerStart))
                    return Result.success()
                }
                SessionEnd.AccountChanged -> {
                    notify("Fetch paused", FetchSession.describe(FetchSession.STOP_ACCOUNT_CHANGED, null, workerStart))
                    return Result.success()
                }
                SessionEnd.Stopped -> return Result.success()
            }
        }
    }

    private fun channel(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(DeleteWorker.CHANNEL, "Runs", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress of a run" })
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundInfo(title: String, text: String? = null): ForegroundInfo {
        channel(applicationContext.getSystemService(NotificationManager::class.java))
        // Counts only, never a message's text, a name, the account id or a header (CLAUDE.md rule 9).
        val n = Notification.Builder(applicationContext, DeleteWorker.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DiscoRedact: $title")
            .setContentText(text ?: "Fetch from Discord")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp())
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notify(title: String, text: String) {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        channel(nm)
        val n = Notification.Builder(applicationContext, DeleteWorker.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("DiscoRedact: $title")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(NOTIFICATION_ID + 1, n)
    }

    companion object {
        const val NOTIFICATION_ID = 1003
    }
}
