package app.discoredact.data

import app.discoredact.core.executor.Candidate
import app.discoredact.core.executor.DryVerdict
import app.discoredact.core.executor.EventRecord
import app.discoredact.core.executor.RunSnapshot
import app.discoredact.core.executor.RunStore
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.selection.GuardRow

/** The executor's store on Room. Every method runs one short write, committed before it returns. */
class RoomRunStore(private val db: AppDatabase) : RunStore {
    private val runs = db.runs()
    private var eventsSinceTrim = 0

    override fun nextCandidate(runId: Long, oldestFirst: Boolean, dryRun: Boolean): Candidate? {
        val row = if (oldestFirst) runs.nextCandidateOldestFirst(runId, dryRun) else runs.nextCandidateNewestFirst(runId, dryRun)
        return row?.let { Candidate(it.messageId, it.channelId, it.position) }
    }

    override fun guardRow(messageId: Long): GuardRow? = runs.guardRow(messageId)?.let { GuardRow(it.messageId, it.status, it.keep) }

    override fun inRun(runId: Long, messageId: Long): Boolean = runs.inRunBlocking(runId, messageId)

    override fun messageOutcome(messageId: Long, status: MessageStatus, httpStatus: Int?, discordCode: Int?, now: Long) =
        runs.messageOutcome(messageId, status, httpStatus, discordCode, now)

    override fun channelState(channelId: Long, state: ChannelState, error: String?) = runs.channelState(channelId, state, error)

    override fun deleted(runId: Long, messageId: Long, now: Long) {
        db.runInTransaction(
            Runnable {
                if (runs.insertDeleteLog(DeleteLogEntity(messageId, runId, now)) != -1L) runs.bumpDeleted(runId)
            },
        )
    }

    override fun dryVerdict(runId: Long, messageId: Long, verdict: DryVerdict) =
        runs.insertResult(RunResultEntity(runId, messageId, verdict.name))

    override fun event(event: EventRecord) {
        runs.insertEvent(
            EventEntity(
                ts = event.ts, runId = event.runId, httpStatus = event.httpStatus, discordCode = event.discordCode,
                bucket = event.bucket, remaining = event.remaining, resetAfter = event.resetAfter, actionTaken = event.actionTaken,
            ),
        )
        if (++eventsSinceTrim >= TRIM_EVERY) {
            eventsSinceTrim = 0
            runs.trimEvents(KEEP_EVENTS)
        }
    }

    override fun deletedSince(sinceMs: Long): Int = runs.deletedSince(sinceMs)

    override fun run(runId: Long): RunSnapshot? = runs.snapshot(runId)?.let { RunSnapshot(it.startedAt, it.endedAt, it.stopReason) }

    override fun runStarted(runId: Long, now: Long) {
        db.runInTransaction(
            Runnable {
                runs.runStarted(runId, now)
                runs.resumeNotBefore(runId, null)
            },
        )
    }

    override fun runEnded(runId: Long, now: Long, reason: String) = runs.runEnded(runId, now, reason)

    override fun runPaused(runId: Long, reason: String, resumeNotBefore: Long?) {
        db.runInTransaction(
            Runnable {
                runs.runStopReason(runId, reason)
                runs.resumeNotBefore(runId, resumeNotBefore)
            },
        )
    }

    companion object {
        const val KEEP_EVENTS = 5_000
        private const val TRIM_EVERY = 200
    }
}
