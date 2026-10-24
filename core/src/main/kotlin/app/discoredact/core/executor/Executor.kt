package app.discoredact.core.executor

import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.pacing.Pacing
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.selection.FrozenSet
import app.discoredact.core.selection.deleteGuard
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** Wall-clock and local time, injectable for tests. */
interface Clock {
    fun nowMs(): Long
    val zone: ZoneId
    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs()), zone)

    /** Epoch millis of the next local midnight after now. */
    fun nextMidnightMs(): Long = localNow().toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

class SystemClock(override val zone: ZoneId = ZoneId.systemDefault()) : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Waits. The worker sleeps for real; tests advance a fake clock instead. Cancellable (Pause cancels a wait). */
fun interface Sleeper {
    suspend fun sleep(ms: Long)
}

/** Why a session ended. The worker turns this into the next enqueue, or into nothing. */
sealed interface SessionEnd {
    /** Every message of the frozen set is settled. */
    data object Done : SessionEnd

    /** The session ran its length; enqueue the next after [breakMs]. */
    data class Break(val breakMs: Long) : SessionEnd

    /** Quiet hours; enqueue the next at [untilMs]. */
    data class QuietUntil(val untilMs: Long) : SessionEnd

    /** The daily cap; enqueue the next at [untilMs] (local midnight, then quiet hours apply). */
    data class CapUntil(val untilMs: Long) : SessionEnd

    /** The run halted; nothing is enqueued, the user resumes by hand (not before [notBeforeMs] when set). */
    data class Halted(val reason: HaltReason, val status: Int?, val code: Int?, val notBeforeMs: Long?) : SessionEnd

    /** Repeated transient failures; the user resumes by hand. */
    data object PausedTransient : SessionEnd

    /** The user stopped the run (its row is ended). */
    data object Stopped : SessionEnd

    /** A real run with no complete header set stored. Nothing was sent. */
    data object NoCredentials : SessionEnd

    /** A fetch only: `GET /users/@me` answered without the account's id, so nothing can be filtered; nothing is read. */
    data object NoAccountId : SessionEnd

    /** A fetch only: the stored headers now belong to another account than the one this fetch began with. */
    data object AccountChanged : SessionEnd
}

/**
 * One session of a run (spec section 8): one request in flight, the delete guard before every send, the pacing of
 * the active preset, the response table of section 9, the tripwire, and a status row written for every message
 * before the next is touched. Pure Kotlin: the store, transport, clock and sleeper are injected.
 *
 * A session is one worker invocation. It returns when the session length is reached, when quiet hours or the daily
 * cap begin, when the run is done, stopped, halted or paused, or when a real run has no credentials.
 *
 * A dry run walks the same order through the same guard and records a verdict per message, but it sends nothing, so
 * the pacing, the quiet hours and the daily cap (which exist to protect the API) do not apply to it.
 */
class Executor(
    private val store: RunStore,
    private val transport: Transport,
    private val credentials: CredentialStore,
    private val config: PacingConfig,
    private val clock: Clock,
    private val sleeper: Sleeper,
    private val random: Random = Random.Default,
    private val tripwire: Tripwire = Tripwire(),
    /**
     * Held around the guard re-read, the send and the row write. The app passes one process-wide mutex, so a worker
     * being cancelled and its replacement (Pause, then Resume) can never both pass the guard for the same message.
     */
    private val sendMutex: Mutex = Mutex(),
) {
    /** Backoff after consecutive transient failures; a further failure pauses the run. */
    val transientBackoffMs: LongArray = longArrayOf(30_000, 120_000, 600_000)

    /** After an edge block the run may not be resumed for an hour (spec section 9). */
    val edgeBlockHoldMs: Long = 60 * 60 * 1000L

    suspend fun runSession(runId: Long, dryRun: Boolean, oldestFirst: Boolean): SessionEnd {
        val run = store.run(runId) ?: error("no run $runId")
        if (run.endedAt != null) return SessionEnd.Stopped
        val frozen = StoreFrozenSet(store, runId)
        val now0 = clock.nowMs()
        store.runStarted(runId, now0)
        // The tripwire's window survives a Pause, a Resume or a kill: it is seeded from the event log.
        if (!dryRun) tripwire.seed(store.invalidResponseTimesSince(now0 - TRIPWIRE_WINDOW_MS))
        val sessionStart = now0
        var multiplier = 1L
        var transientFailures = 0
        var lastChannel: Long? = null
        var sentAny = false
        var pendingWaitMs = 0L
        var pendingWaitWhy: String? = null
        var consecutiveGuardSkips = 0

        while (true) {
            if (store.run(runId)?.endedAt != null) return SessionEnd.Stopped
            val now = clock.nowMs()
            val local = clock.localNow()
            if (!dryRun && config.quietHours.contains(local.toLocalTime())) {
                val until = now + config.quietHours.waitFrom(local).toMillis()
                log(runId, "QUIET_UNTIL $until")
                return SessionEnd.QuietUntil(until)
            }
            if (!dryRun && Pacing.dailyCapReached(config, deletedToday())) {
                val until = clock.nextMidnightMs()
                log(runId, "CAP_UNTIL $until")
                return SessionEnd.CapUntil(until)
            }
            if (!dryRun && Pacing.sessionOver(config, sessionStart, now)) {
                val breakMs = Pacing.breakMs(config, random)
                log(runId, "SESSION_BREAK $breakMs")
                return SessionEnd.Break(breakMs)
            }
            val candidate = store.nextCandidate(runId, oldestFirst, dryRun)
            if (candidate == null) {
                store.runEnded(runId, clock.nowMs(), STOP_DONE)
                log(runId, "DONE")
                return SessionEnd.Done
            }

            if (dryRun) {
                // Same order, same guard, no socket: a verdict for every message the guard passes.
                val row = store.guardRow(candidate.messageId)
                if (row == null || !deleteGuard(row, frozen)) {
                    if (++consecutiveGuardSkips > MAX_GUARD_SKIPS) {
                        store.runPaused(runId, STOP_PAUSED_GUARD, null)
                        log(runId, "PAUSE guard")
                        return SessionEnd.PausedTransient
                    }
                    store.dryVerdict(runId, candidate.messageId, if (row?.keep == true) DryVerdict.SKIPPED_KEPT else DryVerdict.SKIPPED_NOT_PENDING)
                    continue
                }
                consecutiveGuardSkips = 0
                store.dryVerdict(runId, candidate.messageId, DryVerdict.WOULD_DELETE)
                log(runId, "DRY_WOULD_DELETE")
                yield() // a dry run never waits, so this is where a Pause lands
                continue
            }

            // Pacing: the channel switch pause, then the request delay, then any wait the last response asked for.
            if (lastChannel != null && lastChannel != candidate.channelId) {
                val ms = Pacing.channelSwitchMs(config, random)
                wait(runId, ms, "CHANNEL_SWITCH_WAIT")
            }
            if (sentAny) wait(runId, Pacing.requestDelayMs(config, random) * multiplier, "REQUEST_DELAY")
            if (pendingWaitMs > 0) {
                wait(runId, pendingWaitMs, pendingWaitWhy ?: "WAIT")
                pendingWaitMs = 0
                pendingWaitWhy = null
            }
            // The waits may have crossed into quiet hours or a Stop; neither allows this request.
            if (store.run(runId)?.endedAt != null) return SessionEnd.Stopped
            val afterWait = clock.localNow()
            if (config.quietHours.contains(afterWait.toLocalTime())) {
                val until = clock.nowMs() + config.quietHours.waitFrom(afterWait).toMillis()
                log(runId, "QUIET_UNTIL $until")
                return SessionEnd.QuietUntil(until)
            }
            lastChannel = candidate.channelId
            sentAny = true

            val headers = credentials.load()
            if (headers == null) {
                store.runPaused(runId, STOP_NO_CREDENTIALS, null)
                log(runId, "PAUSE no_credentials")
                return SessionEnd.NoCredentials
            }

            // The delete guard immediately before the request (CLAUDE.md rule 5), then the send and its row, as one
            // unit under the process-wide lock: a Pause cancels only between messages, never between a response
            // and its row, and a replacement worker can only re-read the row after this one has written it.
            val result: StepResult? = sendMutex.withLock {
                withContext(NonCancellable) {
                    val row = store.guardRow(candidate.messageId)
                    if (row == null || !deleteGuard(row, frozen)) return@withContext null
                    val response = try {
                        transport.send(DeleteRequest(candidate.channelId, candidate.messageId), headers)
                    } catch (_: TransportException) {
                        null
                    }
                    val at = clock.nowMs()
                    if (response == null) {
                        store.messageOutcome(candidate.messageId, MessageStatus.PENDING, null, null, at)
                        store.event(EventRecord(at, runId, null, null, null, null, null, "NO_RESPONSE"))
                        StepResult(Outcome.Transient(null), null, 0L)
                    } else {
                        val outcome = ResponseClassifier.classify(response)
                        val info = RateLimitInfo.from(response)
                        val code = ResponseClassifier.discordCode(response)
                        val action = record(runId, candidate, response.status, code, outcome, at)
                        store.event(EventRecord(at, runId, response.status, code, info.bucket, info.remaining, info.resetAfterSeconds, action))
                        val resetWait = if (info.remaining != null && info.remaining <= 1 && info.resetAfterSeconds != null) {
                            Pacing.rateLimitWaitMs(config, info.resetAfterSeconds, random)
                        } else {
                            0L
                        }
                        StepResult(outcome, info, resetWait)
                    }
                }
            }
            if (result == null) {
                // Kept, or no longer pending, since the candidate query: skipped silently, never sent.
                if (++consecutiveGuardSkips > MAX_GUARD_SKIPS) {
                    store.runPaused(runId, STOP_PAUSED_GUARD, null)
                    log(runId, "PAUSE guard")
                    return SessionEnd.PausedTransient
                }
                log(runId, "GUARD_SKIP")
                continue
            }
            consecutiveGuardSkips = 0

            when (val o = result.outcome) {
                Outcome.Deleted, Outcome.AlreadyGone, Outcome.SystemMessage -> {
                    transientFailures = 0
                }
                Outcome.ChannelGone, is Outcome.ChannelInaccessible -> {
                    transientFailures = 0
                    if (o is Outcome.ChannelInaccessible && tripwire.record(clock.nowMs())) return halt(runId, HaltReason.TRIPWIRE, 403, o.code)
                }
                is Outcome.RateLimited -> {
                    val waitMs = o.retryAfterMs + Pacing.rateLimitWaitMs(config, 0.0, random)
                    pendingWaitMs = maxOf(pendingWaitMs, waitMs)
                    pendingWaitWhy = "RATE_LIMIT_WAIT"
                    if (o.countsAsInvalid) {
                        multiplier = 2
                        if (tripwire.record(clock.nowMs())) return halt(runId, HaltReason.TRIPWIRE, 429, null)
                    }
                }
                is Outcome.Halt -> {
                    if (o.reason == HaltReason.TOKEN_INVALID || o.status == 403) tripwire.record(clock.nowMs())
                    return halt(runId, o.reason, o.status, o.code)
                }
                is Outcome.Transient -> {
                    transientFailures++
                    if (transientFailures > transientBackoffMs.size) {
                        store.runPaused(runId, STOP_PAUSED_TRANSIENT, null)
                        log(runId, "PAUSE transient")
                        return SessionEnd.PausedTransient
                    }
                    pendingWaitMs = maxOf(pendingWaitMs, transientBackoffMs[transientFailures - 1])
                    pendingWaitWhy = "TRANSIENT_BACKOFF"
                }
            }
            if (result.resetWaitMs > pendingWaitMs) {
                pendingWaitMs = result.resetWaitMs
                pendingWaitWhy = "WAIT_RESET"
            }
        }
    }

    private class StepResult(val outcome: Outcome, val info: RateLimitInfo?, val resetWaitMs: Long)

    /** Writes the message's and channel's rows for [outcome]; returns the event's action text. */
    private fun record(runId: Long, c: Candidate, status: Int, code: Int?, outcome: Outcome, at: Long): String = when (outcome) {
        Outcome.Deleted -> {
            store.messageOutcome(c.messageId, MessageStatus.DELETED, status, code, at)
            store.deleted(runId, c.messageId, at)
            "DELETED"
        }
        Outcome.AlreadyGone -> {
            store.messageOutcome(c.messageId, MessageStatus.ALREADY_GONE, status, code, at)
            "ALREADY_GONE"
        }
        Outcome.SystemMessage -> {
            store.messageOutcome(c.messageId, MessageStatus.SKIPPED_SYSTEM, status, code, at)
            "SKIPPED_SYSTEM"
        }
        Outcome.ChannelGone -> {
            store.messageOutcome(c.messageId, MessageStatus.PENDING, status, code, at)
            store.channelState(c.channelId, ChannelState.GONE, "404 $code")
            "CHANNEL_GONE"
        }
        is Outcome.ChannelInaccessible -> {
            store.messageOutcome(c.messageId, MessageStatus.PENDING, status, code, at)
            store.channelState(c.channelId, ChannelState.INACCESSIBLE, "403 $code")
            "CHANNEL_INACCESSIBLE"
        }
        is Outcome.RateLimited -> {
            store.messageOutcome(c.messageId, MessageStatus.PENDING, status, code, at)
            "RATE_LIMITED ${outcome.scope.name.lowercase()}"
        }
        is Outcome.Halt -> {
            val st = if (outcome.reason == HaltReason.UNKNOWN_4XX) MessageStatus.FAILED else MessageStatus.PENDING
            store.messageOutcome(c.messageId, st, status, code, at)
            "HALT ${outcome.reason.key}"
        }
        is Outcome.Transient -> {
            store.messageOutcome(c.messageId, MessageStatus.PENDING, status, code, at)
            "TRANSIENT"
        }
    }

    private fun halt(runId: Long, reason: HaltReason, status: Int?, code: Int?): SessionEnd {
        val notBefore = if (reason == HaltReason.EDGE_BLOCK) clock.nowMs() + edgeBlockHoldMs else null
        store.runPaused(runId, reason.stopReason, notBefore)
        log(runId, "HALT ${reason.key}" + (status?.let { " $it" } ?: "") + (code?.let { " $it" } ?: ""))
        return SessionEnd.Halted(reason, status, code, notBefore)
    }

    private suspend fun wait(runId: Long, ms: Long, why: String) {
        if (ms <= 0) return
        log(runId, "$why $ms")
        sleeper.sleep(ms)
    }

    private fun log(runId: Long, action: String) =
        store.event(EventRecord(clock.nowMs(), runId, null, null, null, null, null, action))

    private fun deletedToday(): Int {
        val midnight = clock.localNow().toLocalDate().atStartOfDay(clock.zone).toInstant().toEpochMilli()
        return store.deletedSince(midnight)
    }

    /** The frozen set as the store holds it. It never grows: `run_messages` is written once, at the freeze. */
    private class StoreFrozenSet(private val store: RunStore, override val runId: Long) : FrozenSet {
        override val size: Int get() = -1
        override fun contains(messageId: Long): Boolean = store.inRun(runId, messageId)
    }

    companion object {
        const val STOP_DONE = "done"
        const val STOP_STOPPED = "stopped"
        const val STOP_PAUSED_TRANSIENT = "paused:transient"
        const val STOP_PAUSED_GUARD = "paused:guard"
        const val STOP_NO_CREDENTIALS = "paused:no_credentials"
        const val STOP_PAUSED_USER = "paused:user"
        const val STOP_WAITING_QUIET = "waiting:quiet"
        const val STOP_WAITING_CAP = "waiting:cap"
        const val STOP_WAITING_APP = "waiting:app"
        private const val MAX_GUARD_SKIPS = 10_000
        const val TRIPWIRE_WINDOW_MS = 10 * 60 * 1000L

        /** Whether the user may resume a run in this state: not ended, and either started once or paused before starting. */
        fun isResumable(stopReason: String?, endedAt: Long?, startedAt: Long?): Boolean =
            endedAt == null && (startedAt != null || stopReason != null)

        /** What a `stop_reason` means for the user. */
        fun describe(stopReason: String?, endedAt: Long?, startedAt: Long?): String = when {
            endedAt != null && stopReason == STOP_DONE -> "Finished"
            endedAt != null && stopReason == STOP_STOPPED -> "Stopped"
            endedAt != null && stopReason == "superseded" -> "Superseded by a newer review"
            endedAt != null -> "Ended ($stopReason)"
            stopReason == null && startedAt == null -> "Frozen, not started"
            stopReason == null -> "Running, or interrupted by a kill or reboot (Resume continues it)"
            stopReason == STOP_PAUSED_USER -> "Paused"
            stopReason == STOP_PAUSED_TRANSIENT -> "Paused after repeated server or network errors; Resume retries"
            stopReason == STOP_PAUSED_GUARD -> "Paused: the delete guard kept refusing; review the selection"
            stopReason == STOP_NO_CREDENTIALS -> "No credentials stored; import them on the Credentials screen (Settings)"
            stopReason == STOP_WAITING_QUIET -> "Waiting for quiet hours to end"
            stopReason == STOP_WAITING_CAP -> "Daily cap reached; continues tomorrow"
            stopReason == STOP_WAITING_APP -> "Waiting: a run continues only while the app is open"
            stopReason == HaltReason.TOKEN_INVALID.stopReason -> "Halted: the token was rejected (401). Capture the headers again, then Resume"
            stopReason == HaltReason.CAPTCHA.stopReason -> "Halted: Discord asked for a captcha. The app never solves one; wait, then Resume"
            stopReason == HaltReason.EDGE_BLOCK.stopReason -> "Halted: an edge block (a non-API response). Resume by hand, not before an hour"
            stopReason == HaltReason.TRIPWIRE.stopReason -> "Halted: five invalid responses in ten minutes. Something is wrong; check before Resume"
            stopReason == HaltReason.UNKNOWN_4XX.stopReason -> "Halted: an unexpected 4xx response (see the last response)"
            stopReason == HaltReason.UNEXPECTED_BODY.stopReason -> "Halted: Discord answered with a body of a shape the app does not know; nothing of it was kept"
            else -> stopReason
        }
    }
}
