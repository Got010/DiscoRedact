package app.discoredact.ui

/**
 * The decisions around "Wipe everything" that need no Android: whether the run worker is out of the way, and what the
 * Settings screen says while something is writing. Pure, so the JVM tests cover them; the ViewModel only feeds it.
 */
object WipeGate {
    /** How long a wipe waits for a cancelled run worker to disappear from WorkManager before it refuses. */
    const val WORKER_WAIT_MS = 15_000L

    const val STILL_WRITING = "A run is still writing; try again in a moment. Nothing was wiped."

    sealed interface Decision {
        /** No worker is enqueued or running: the wipe may go on. */
        data object Ready : Decision

        /** A worker is still active and the wait is not over: ask again shortly. */
        data object WaitForWorker : Decision

        /** The wait is over and a worker is still active: wipe nothing and tell the user. */
        data class Refuse(val reason: String) : Decision
    }

    /** [workerActive] is WorkManager's answer for the run worker's unique name; [waitedMs] is the time since the cancel. */
    fun decide(workerActive: Boolean, waitedMs: Long): Decision = when {
        !workerActive -> Decision.Ready
        waitedMs < WORKER_WAIT_MS -> Decision.WaitForWorker
        else -> Decision.Refuse(STILL_WRITING)
    }

    /**
     * The line under the Purge and Wipe buttons while they are disabled, or null when nothing is writing. A running
     * run is named; it must be paused or stopped on the Run screen first.
     */
    fun busyNotice(importRunning: Boolean, filterApplying: Boolean, freezing: Boolean, workerRunning: Boolean, runId: Long?): String? {
        val parts = buildList {
            if (importRunning) add("an import")
            if (filterApplying) add("a filter")
            if (freezing) add("a freeze")
            if (workerRunning) add(if (runId != null) "run #$runId" else "a run")
        }
        if (parts.isEmpty()) return null
        val joined = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        val subject = joined.replaceFirstChar { it.uppercase() }
        val verb = if (parts.size == 1) "is" else "are"
        val tail = if (workerRunning) " Pause or Stop the run on the Run screen first." else ""
        return "$subject $verb running; Purge and Wipe wait until it ends.$tail"
    }
}
