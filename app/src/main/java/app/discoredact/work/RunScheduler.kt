package app.discoredact.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/** Enqueues the one run worker under one unique name, so two sessions can never run at once (CLAUDE.md rule 3). */
class RunScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context.applicationContext)

    /** Starts or resumes [runId] now. KEEP: if a worker for the unique name is already enqueued or running, nothing changes. */
    fun start(runId: Long, dryRun: Boolean) {
        val req = OneTimeWorkRequestBuilder<DeleteWorker>()
            .setInputData(workDataOf(DeleteWorker.KEY_RUN_ID to runId))
            .setConstraints(if (dryRun) Constraints.NONE else Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, req)
    }

    /** The next session at [atMs] (quiet hours, the daily cap). Replaces any earlier enqueue for the name. */
    fun enqueueAt(runId: Long, atMs: Long) {
        val delay = (atMs - System.currentTimeMillis()).coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<DeleteWorker>()
            .setInputData(workDataOf(DeleteWorker.KEY_RUN_ID to runId))
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
    }

    /** Pause and Stop both cancel the worker; the executor finishes the request in flight and its row first. */
    fun cancel() {
        wm.cancelUniqueWork(UNIQUE)
    }

    /** Whether a worker for the run is enqueued or running. */
    fun observeActive(): Flow<WorkState> = wm.getWorkInfosForUniqueWorkFlow(UNIQUE).map { infos ->
        val info = infos.firstOrNull { !it.state.isFinished }
        when (info?.state) {
            WorkInfo.State.RUNNING -> WorkState.RUNNING
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> WorkState.SCHEDULED
            else -> WorkState.NONE
        }
    }

    enum class WorkState { NONE, SCHEDULED, RUNNING }

    companion object {
        const val UNIQUE = "delete-run"
    }
}
