package app.discoredact.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Enqueues the one fetch worker under its own unique name. It and the run worker both hold the process-wide worker
 * mutex for their whole bodies, and the screens refuse to start one while the other is active (StartRules), so a read
 * and a delete are never in flight together (CLAUDE.md rule 3).
 */
class FetchScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context.applicationContext)

    private fun request(delayMs: Long): OneTimeWorkRequest = OneTimeWorkRequestBuilder<FetchWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        .build()

    /** Starts or resumes the fetch now. KEEP: a worker already enqueued or running is left as it is. */
    fun start() {
        wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request(0))
    }

    /** The next session at [atMs] (quiet hours, the daily cap). Replaces any earlier enqueue for the name. */
    fun enqueueAt(atMs: Long) {
        wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request(atMs - System.currentTimeMillis()))
    }

    /** Pause and Stop cancel the worker; the session finishes the read in flight and its rows first. */
    fun cancel() {
        wm.cancelUniqueWork(UNIQUE)
    }

    fun observeActive(): Flow<RunScheduler.WorkState> = wm.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
        val info = infos.firstOrNull { !it.state.isFinished }
        when (info?.state) {
            WorkInfo.State.RUNNING -> RunScheduler.WorkState.RUNNING
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> RunScheduler.WorkState.SCHEDULED
            else -> RunScheduler.WorkState.NONE
        }
    }

    companion object {
        const val UNIQUE = "fetch"
    }
}
