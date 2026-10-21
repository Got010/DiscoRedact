package app.discoredact.core.executor

import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.selection.GuardRow

/** The next message of a run that is still pending, in run order. */
data class Candidate(val messageId: Long, val channelId: Long, val position: Int)

/** One row of the rolling event log (spec section 5). No header value, no body text, ever. */
data class EventRecord(
    val ts: Long,
    val runId: Long,
    val httpStatus: Int?,
    val discordCode: Int?,
    val bucket: String?,
    val remaining: Int?,
    val resetAfter: Double?,
    val actionTaken: String,
)

/**
 * A dry run's verdict for one message of the frozen set. The executor writes [WOULD_DELETE] for every message the
 * guard passes; a message it never reaches (kept, or no longer pending) gets no row, and the report shows it as
 * skipped. The two skip values exist for the guard's own race (a row that changed between the query and the re-read).
 */
enum class DryVerdict { WOULD_DELETE, SKIPPED_KEPT, SKIPPED_NOT_PENDING }

/** How a run row reads at a point in time. */
data class RunSnapshot(val startedAt: Long?, val endedAt: Long?, val stopReason: String?)

/**
 * Everything the executor reads and writes. The app implements it on Room; tests use an in-memory one. Every write is
 * committed before the executor moves on (CLAUDE.md rule 6), so a kill or reboot resumes from the rows.
 */
interface RunStore {
    /**
     * The next candidate of [runId]: in the frozen set, status PENDING, keep false, in a channel that is neither GONE
     * nor INACCESSIBLE, in run order (newest first unless [oldestFirst]). Null when nothing is left. A dry run changes
     * no message status, so with [dryRun] the messages that already have a verdict for this run are excluded instead.
     */
    fun nextCandidate(runId: Long, oldestFirst: Boolean, dryRun: Boolean): Candidate?

    /** The message row re-read immediately before a request (the delete guard, CLAUDE.md rule 5). */
    fun guardRow(messageId: Long): GuardRow?

    fun inRun(runId: Long, messageId: Long): Boolean

    /** Writes the message's status, last HTTP status and Discord code, bumps `attempts`, sets `updated_at`. */
    fun messageOutcome(messageId: Long, status: MessageStatus, httpStatus: Int?, discordCode: Int?, now: Long)

    fun channelState(channelId: Long, state: ChannelState, error: String?)

    /** Records a real deletion (the daily cap and the reports count these). */
    fun deleted(runId: Long, messageId: Long, now: Long)

    fun dryVerdict(runId: Long, messageId: Long, verdict: DryVerdict)

    fun event(event: EventRecord)

    /** Real deletions recorded at or after [sinceMs]. */
    fun deletedSince(sinceMs: Long): Int

    /** Timestamps of the invalid responses (401, 403, 429 not shared) logged at or after [sinceMs], to seed the tripwire. */
    fun invalidResponseTimesSince(sinceMs: Long): List<Long>

    fun run(runId: Long): RunSnapshot?

    /** Sets `started_at` when it is null and clears a pause or halt reason. */
    fun runStarted(runId: Long, now: Long)

    /** Ends the run: `ended_at` and `stop_reason`. */
    fun runEnded(runId: Long, now: Long, reason: String)

    /** A pause or halt: `stop_reason` set, `ended_at` left null so the run can be resumed; [resumeNotBefore] for edge blocks. */
    fun runPaused(runId: Long, reason: String, resumeNotBefore: Long?)
}
