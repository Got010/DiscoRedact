package app.discoredact.core.selection

/** Why a Start or Resume was refused. The screen words each one; none carries a value from the credentials. */
enum class StartRefusal {
    /** Another run is started and not ended; it must be stopped (or finish) first. */
    OTHER_RUN_OPEN,

    /** The run is held after an edge block and may not continue before the given time. */
    HELD,

    /** A real run that has not started needs a complete header set and none is stored (or it cannot be read). */
    NO_CREDENTIALS_START,

    /** A real run that has started needs a complete header set to continue. */
    NO_CREDENTIALS_RESUME,
}

sealed interface StartDecision {
    /** Nothing is started. [openRunId] is set for [StartRefusal.OTHER_RUN_OPEN], [notBefore] for [StartRefusal.HELD]. */
    data class Refuse(val reason: StartRefusal, val openRunId: Long? = null, val notBefore: Long? = null) : StartDecision

    /** The run has started before: continue it with the options it was started with. */
    data object Resume : StartDecision

    /** The run has never started: record its options and start it. */
    data object Start : StartDecision
}

/**
 * Whether a frozen run may be started or resumed (brief decision 1: an open run first; decision 5: a real run
 * needs credentials). Pure, so the open-run refusal is tested on the JVM. Fail closed: any doubt refuses.
 */
object StartRules {
    /**
     * @param runId the run the user asked for
     * @param openRunId the run that is started and not ended, if any
     * @param startedAt that run's `started_at`; null if it never started
     * @param dryRun the run's mode: its stored option once it has options, else the Dry run toggle now
     * @param hasHeaders whether a complete, readable header set is stored
     * @param holdUntil the run's `resume_not_before`, if any
     * @param now the current time, epoch milliseconds
     */
    fun decide(
        runId: Long,
        openRunId: Long?,
        startedAt: Long?,
        dryRun: Boolean,
        hasHeaders: Boolean,
        holdUntil: Long?,
        now: Long,
    ): StartDecision {
        if (openRunId != null && openRunId != runId) return StartDecision.Refuse(StartRefusal.OTHER_RUN_OPEN, openRunId = openRunId)
        val started = startedAt != null
        if (holdUntil != null && holdUntil > now) return StartDecision.Refuse(StartRefusal.HELD, notBefore = holdUntil)
        if (!dryRun && !hasHeaders) {
            return StartDecision.Refuse(if (started) StartRefusal.NO_CREDENTIALS_RESUME else StartRefusal.NO_CREDENTIALS_START)
        }
        return if (started) StartDecision.Resume else StartDecision.Start
    }
}
