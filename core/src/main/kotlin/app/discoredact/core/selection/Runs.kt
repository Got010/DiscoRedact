package app.discoredact.core.selection

/** What the run rules need from a `runs` row. */
data class RunRow(val runId: Long, val startedAt: Long?, val endedAt: Long?)

/**
 * Which frozen run may be executed. Freezing a new run supersedes every run that never started, so at most one
 * unstarted run is open at a time: the newest. The executor (M2) runs only [runnable].
 */
object RunRules {
    const val SUPERSEDED = "superseded"

    /** The runs a new freeze closes as superseded: every run that has not started and has not ended. */
    fun toSupersede(runs: List<RunRow>): List<Long> =
        runs.filter { it.startedAt == null && it.endedAt == null }.map { it.runId }.sorted()

    /** The only run the executor may start: the newest one that has not started and has not ended. */
    fun runnable(runs: List<RunRow>): Long? =
        runs.filter { it.startedAt == null && it.endedAt == null }.maxOfOrNull { it.runId }
}
