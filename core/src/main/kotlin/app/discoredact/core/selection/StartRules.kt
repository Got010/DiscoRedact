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

    /** The Fetch import's worker is enqueued or running: one worker at a time (CLAUDE.md rule 3). */
    FETCH_ACTIVE,
}

/** Why "Start fetch" or a fetch's Resume was refused. None carries a value from the credentials. */
enum class FetchRefusal {
    /** Wipe everything is running. */
    WIPING,

    /** A run's worker is enqueued or running: one worker at a time (CLAUDE.md rule 3). */
    RUN_ACTIVE,

    /** The fetch is held after an edge block and may not continue before the given time. */
    HELD,

    /** Every read needs the stored headers, and none are stored (or they cannot be read). */
    NO_CREDENTIALS,
}

sealed interface FetchDecision {
    data class Refuse(val reason: FetchRefusal, val notBefore: Long? = null) : FetchDecision

    /** An open fetch (begun, not ended): continue it where its progress rows say. */
    data object Resume : FetchDecision

    /** No open fetch: begin a new one (its progress starts empty, so every listed DM is read from the newest page). */
    data object Start : FetchDecision
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
     * @param fetchActive whether the Fetch import's worker is enqueued or running
     */
    fun decide(
        runId: Long,
        openRunId: Long?,
        startedAt: Long?,
        dryRun: Boolean,
        hasHeaders: Boolean,
        holdUntil: Long?,
        now: Long,
        fetchActive: Boolean = false,
    ): StartDecision {
        if (fetchActive) return StartDecision.Refuse(StartRefusal.FETCH_ACTIVE)
        if (openRunId != null && openRunId != runId) return StartDecision.Refuse(StartRefusal.OTHER_RUN_OPEN, openRunId = openRunId)
        val started = startedAt != null
        if (holdUntil != null && holdUntil > now) return StartDecision.Refuse(StartRefusal.HELD, notBefore = holdUntil)
        if (!dryRun && !hasHeaders) {
            return StartDecision.Refuse(if (started) StartRefusal.NO_CREDENTIALS_RESUME else StartRefusal.NO_CREDENTIALS_START)
        }
        return if (started) StartDecision.Resume else StartDecision.Start
    }

    /**
     * Whether "Start fetch" (or a fetch's Resume) may go ahead (B34 history brief, section 4): never during a wipe or
     * while a run's worker is enqueued or running, never before an edge block's hold ends, never without stored
     * headers (the same refusal a real run gives). A fetch is not a run: the dry-run latch plays no part.
     *
     * @param fetchOpen whether a fetch was begun and not ended (paused, halted, waiting or interrupted)
     */
    fun decideFetch(
        wiping: Boolean,
        runWorkerActive: Boolean,
        hasHeaders: Boolean,
        fetchOpen: Boolean,
        holdUntil: Long?,
        now: Long,
    ): FetchDecision {
        if (wiping) return FetchDecision.Refuse(FetchRefusal.WIPING)
        if (runWorkerActive) return FetchDecision.Refuse(FetchRefusal.RUN_ACTIVE)
        if (fetchOpen && holdUntil != null && holdUntil > now) return FetchDecision.Refuse(FetchRefusal.HELD, holdUntil)
        if (!hasHeaders) return FetchDecision.Refuse(FetchRefusal.NO_CREDENTIALS)
        return if (fetchOpen) FetchDecision.Resume else FetchDecision.Start
    }
}
