package app.discoredact.core.history

import app.discoredact.core.executor.ApiResponse
import app.discoredact.core.executor.Clock
import app.discoredact.core.executor.CredentialStore
import app.discoredact.core.executor.EdgeHold
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.HeaderSet
import app.discoredact.core.executor.Outcome
import app.discoredact.core.executor.RateLimitInfo
import app.discoredact.core.executor.RefusedBeforeSending
import app.discoredact.core.executor.ResponseClassifier
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.Sleeper
import app.discoredact.core.executor.Transport
import app.discoredact.core.executor.TransportException
import app.discoredact.core.executor.Tripwire
import app.discoredact.core.importer.ImportLimits
import app.discoredact.core.model.ChannelState
import app.discoredact.core.pacing.Pacing
import app.discoredact.core.pacing.PacingConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * One session of the Fetch import (B34 API history, sections 3 and 4), the read-side twin of [Executor.runSession]: one
 * request in flight under the same process-wide lock, the active preset's delay and jitter between requests, the
 * channel-switch pause between DMs, `X-RateLimit-*` honoured, 429 waited as the table says, the daily cap (reads and
 * deletes of the day together) and quiet hours, the tripwire seeded from the same event log, the halts of the
 * response table, and each page's rows and progress written before the next request.
 *
 * The order: `GET /users/@me` (once per fetch, and again only when the stored capture changed), `GET
 * /users/@me/channels` (once per fetch), then each listed DM's history, newest page first, until a page has fewer than
 * 100 rows. Only rows whose author is the account are written ([HistoryRows.own]).
 */
class FetchSession(
    private val store: FetchStore,
    private val transport: Transport,
    private val credentials: CredentialStore,
    private val config: PacingConfig,
    private val clock: Clock,
    private val sleeper: Sleeper,
    private val random: Random = Random.Default,
    private val tripwire: Tripwire = Tripwire(),
    /** The app passes the run's process-wide mutex: a read and a delete never interleave, nor two reads. */
    private val sendMutex: Mutex = Mutex(),
    private val limits: ImportLimits = ImportLimits(),
) {
    val transientBackoffMs: LongArray = longArrayOf(30_000, 120_000, 600_000)
    /** The app-wide edge hold (EdgeHold): the fetch's own notBefore says the same as the shared event log. */
    val edgeBlockHoldMs: Long = EdgeHold.HOLD_MS

    private sealed interface Res {
        /** The id, the list or a page was written. */
        data object Progress : Res
        data class Failed(val outcome: Outcome) : Res
        data object NoAccountId : Res
        data object AccountChanged : Res
        data class BodyHalt(val reason: HaltReason, val status: Int) : Res
    }

    private class StepResult(val res: Res, val resetWaitMs: Long)

    suspend fun runSession(): SessionEnd {
        val first = store.fetch() ?: return SessionEnd.Stopped
        if (first.endedAt != null) return SessionEnd.Stopped
        val now0 = clock.nowMs()
        store.fetchStarted(now0)
        tripwire.seed(store.invalidResponseTimesSince(now0 - Executor.TRIPWIRE_WINDOW_MS))
        var multiplier = 1L
        var transientFailures = 0
        var lastChannel: Long? = null
        var sentAny = false
        var pendingWaitMs = 0L
        var pendingWaitWhy: String? = null
        var consecutiveSkips = 0

        while (true) {
            if (store.fetch()?.endedAt != null) return SessionEnd.Stopped
            val now = clock.nowMs()
            val local = clock.localNow()
            if (config.quietHours.contains(local.toLocalTime())) {
                val until = now + config.quietHours.waitFrom(local).toMillis()
                log("QUIET_UNTIL $until")
                return SessionEnd.QuietUntil(until)
            }
            if (Pacing.dailyCapReached(config, requestsToday())) {
                val until = clock.nextMidnightMs()
                log("CAP_UNTIL $until")
                return SessionEnd.CapUntil(until)
            }
            if (Pacing.sessionOver(config, now0, now)) {
                val breakMs = Pacing.breakMs(config, random)
                log("SESSION_BREAK $breakMs")
                return SessionEnd.Break(breakMs)
            }
            val headers = credentials.load()
            if (headers == null) {
                store.fetchPaused(Executor.STOP_NO_CREDENTIALS, null)
                log("PAUSE no_credentials")
                return SessionEnd.NoCredentials
            }
            val step = nextStep(headers)
            if (step == null) {
                store.fetchEnded(clock.nowMs(), Executor.STOP_DONE)
                log("DONE")
                return SessionEnd.Done
            }

            // Pacing: the channel switch pause, then the request delay, then any wait the last response asked for.
            if (step is GetRequest.Messages && lastChannel != null && lastChannel != step.channelId) {
                wait(Pacing.channelSwitchMs(config, random), "CHANNEL_SWITCH_WAIT")
            }
            if (sentAny) wait(Pacing.requestDelayMs(config, random) * multiplier, "REQUEST_DELAY")
            if (pendingWaitMs > 0) {
                wait(pendingWaitMs, pendingWaitWhy ?: "WAIT")
                pendingWaitMs = 0
                pendingWaitWhy = null
            }
            // The waits may have crossed into quiet hours or a Stop; neither allows this request.
            if (store.fetch()?.endedAt != null) return SessionEnd.Stopped
            val afterWait = clock.localNow()
            if (config.quietHours.contains(afterWait.toLocalTime())) {
                val until = clock.nowMs() + config.quietHours.waitFrom(afterWait).toMillis()
                log("QUIET_UNTIL $until")
                return SessionEnd.QuietUntil(until)
            }
            if (step is GetRequest.Messages) lastChannel = step.channelId
            sentAny = true

            // The read guard, the request and its rows as one unit under the process-wide lock: a Pause cancels only
            // between requests, and a replacement worker re-reads the progress only after this one has written it.
            val result: StepResult? = sendMutex.withLock {
                withContext(NonCancellable) {
                    if (store.fetch()?.endedAt != null || nextStep(headers) != step) return@withContext null
                    var refused = false
                    val response = try {
                        transport.get(step, headers)
                    } catch (_: TransportException) {
                        null
                    } catch (e: Exception) {
                        // The transport's own refusal (nothing was sent): a named halt, never an uncaught exception.
                        if (e !is RefusedBeforeSending) throw e
                        refused = true
                        null
                    }
                    val at = clock.nowMs()
                    if (refused) {
                        store.event(FetchEventRecord(at, null, null, null, null, null, "HALT ${HaltReason.REFUSED_LOCALLY.key}"))
                        return@withContext StepResult(Res.Failed(Outcome.Halt(HaltReason.REFUSED_LOCALLY, null, null)), 0L)
                    }
                    store.readSent(at, dayStart())
                    if (response == null) {
                        store.event(FetchEventRecord(at, null, null, null, null, null, "NO_RESPONSE"))
                        StepResult(Res.Failed(Outcome.Transient(null)), 0L)
                    } else {
                        handle(step, response, headers, at)
                    }
                }
            }
            if (result == null) {
                // Another worker read this step first (or the fetch ended): nothing was sent; look again.
                if (++consecutiveSkips > MAX_SKIPS) {
                    store.fetchPaused(Executor.STOP_PAUSED_GUARD, null)
                    log("PAUSE guard")
                    return SessionEnd.PausedTransient
                }
                log("GUARD_SKIP")
                continue
            }
            consecutiveSkips = 0

            when (val r = result.res) {
                Res.Progress -> transientFailures = 0
                Res.NoAccountId -> {
                    store.fetchPaused(STOP_NO_ACCOUNT_ID, null)
                    log("PAUSE no_account_id")
                    return SessionEnd.NoAccountId
                }
                Res.AccountChanged -> {
                    store.fetchPaused(STOP_ACCOUNT_CHANGED, null)
                    log("PAUSE account_changed")
                    return SessionEnd.AccountChanged
                }
                is Res.BodyHalt -> return halt(r.reason, r.status, null)
                is Res.Failed -> when (val o = r.outcome) {
                    Outcome.ChannelGone, is Outcome.ChannelInaccessible -> {
                        transientFailures = 0
                        if (o is Outcome.ChannelInaccessible && tripwire.record(clock.nowMs())) return halt(HaltReason.TRIPWIRE, 403, o.code)
                    }
                    is Outcome.RateLimited -> {
                        pendingWaitMs = maxOf(pendingWaitMs, o.retryAfterMs + Pacing.rateLimitWaitMs(config, 0.0, random))
                        pendingWaitWhy = "RATE_LIMIT_WAIT"
                        if (o.countsAsInvalid) {
                            multiplier = 2
                            if (tripwire.record(clock.nowMs())) return halt(HaltReason.TRIPWIRE, 429, null)
                        }
                    }
                    is Outcome.Halt -> {
                        if (o.reason == HaltReason.TOKEN_INVALID || o.status == 403) tripwire.record(clock.nowMs())
                        return halt(o.reason, o.status, o.code)
                    }
                    is Outcome.Transient -> {
                        transientFailures++
                        if (transientFailures > transientBackoffMs.size) {
                            store.fetchPaused(Executor.STOP_PAUSED_TRANSIENT, null)
                            log("PAUSE transient")
                            return SessionEnd.PausedTransient
                        }
                        pendingWaitMs = maxOf(pendingWaitMs, transientBackoffMs[transientFailures - 1])
                        pendingWaitWhy = "TRANSIENT_BACKOFF"
                    }
                    // ReadClassifier never answers these for a read; were it to, halt rather than guess.
                    Outcome.Deleted, Outcome.AlreadyGone, Outcome.SystemMessage -> return halt(HaltReason.UNKNOWN_4XX, null, null)
                }
            }
            if (result.resetWaitMs > pendingWaitMs) {
                pendingWaitMs = result.resetWaitMs
                pendingWaitWhy = "WAIT_RESET"
            }
        }
    }

    /**
     * The next read, from the rows alone: the account id while it is unknown or was read with another capture, then
     * the DM list once, then the first DM not done. Null when every listed DM is done (or no fetch exists).
     */
    private fun nextStep(headers: HeaderSet): GetRequest? {
        val f = store.fetch() ?: return null
        if (f.accountId == null || f.accountCapturedAt != headers.capturedAt) return GetRequest.Me
        if (f.listedAt == null) return GetRequest.MyChannels
        return store.nextChannel()?.let { FetchPlan.request(it) }
    }

    /** Classifies [response], writes what it says, logs one event; under the lock, before the next request. */
    private fun handle(step: GetRequest, response: ApiResponse, headers: HeaderSet, at: Long): StepResult {
        val info = RateLimitInfo.from(response)
        val ok = response.status in 200..299
        // A page's body is never parsed for a Discord error code (it is an array of messages).
        val code = if (ok) null else ResponseClassifier.discordCode(response)
        val (res, action) = when (val read = ReadClassifier.classify(response, step)) {
            ReadOutcome.Ok -> onOk(step, response, headers, at)
            is ReadOutcome.Failed -> {
                val o = read.outcome
                if (step is GetRequest.Messages) {
                    when (o) {
                        Outcome.ChannelGone -> store.channelOutcome(step.channelId, ChannelState.GONE, "404 $code", at)
                        is Outcome.ChannelInaccessible -> store.channelOutcome(step.channelId, ChannelState.INACCESSIBLE, "403 $code", at)
                        else -> Unit
                    }
                }
                Res.Failed(o) to actionOf(o)
            }
        }
        store.event(FetchEventRecord(at, response.status, code, info.bucket, info.remaining, info.resetAfterSeconds, action))
        val resetWait = if (info.remaining != null && info.remaining <= 1 && info.resetAfterSeconds != null) {
            Pacing.rateLimitWaitMs(config, info.resetAfterSeconds, random)
        } else {
            0L
        }
        return StepResult(res, resetWait)
    }

    private fun onOk(step: GetRequest, response: ApiResponse, headers: HeaderSet, at: Long): Pair<Res, String> {
        val body = response.body
        fun bad(notJson: Boolean): Pair<Res, String> {
            val reason = if (notJson) HaltReason.EDGE_BLOCK else HaltReason.UNEXPECTED_BODY
            return Res.BodyHalt(reason, response.status) to "HALT ${reason.key}"
        }
        return when (step) {
            GetRequest.Me -> {
                if (ReadBodies.notJson(body)) return bad(true)
                val id = MeParser.id(body) ?: return Res.NoAccountId to "FETCH_ME no_id"
                val known = store.fetch()?.accountId
                if (known != null && known != id) return Res.AccountChanged to "FETCH_ME other_account"
                store.accountId(id, headers.capturedAt)
                Res.Progress to "FETCH_ME"
            }
            GetRequest.MyChannels -> {
                val list = ChannelsParser.dms(body) ?: return bad(ReadBodies.notJson(body))
                store.channelsSeen(list.channels, at)
                Res.Progress to "FETCH_CHANNELS ${list.channels.size} skipped ${list.skippedOther + list.malformed}"
            }
            is GetRequest.Messages -> {
                val accountId = store.fetch()?.accountId ?: return Res.NoAccountId to "FETCH_PAGE no_id"
                when (val page = HistoryRows.own(accountId, step.channelId, body, limits)) {
                    is PageResult.Bad -> bad(page.notJson)
                    is PageResult.Page -> when (val next = FetchPlan.advance(step.channelId, step.before, page)) {
                        FetchPlan.Advance.Stuck -> Res.BodyHalt(HaltReason.UNEXPECTED_BODY, response.status) to "HALT ${HaltReason.UNEXPECTED_BODY.key}"
                        is FetchPlan.Advance.Next -> {
                            store.pageWritten(step.channelId, page.own, next.progress.oldestFetchedId, next.progress.done, at)
                            // A page read proves access again: a DM an earlier 403 or 404 marked is open to runs again.
                            store.channelReadable(step.channelId, at)
                            Res.Progress to "FETCH_PAGE ${page.pageSize} own ${page.own.size}" + if (next.progress.done) " done" else ""
                        }
                    }
                }
            }
        }
    }

    /** The event text, in the run's words where they apply ("RATE_LIMITED shared" is what the tripwire seed excludes). */
    private fun actionOf(o: Outcome): String = when (o) {
        Outcome.ChannelGone -> "CHANNEL_GONE"
        is Outcome.ChannelInaccessible -> "CHANNEL_INACCESSIBLE"
        is Outcome.RateLimited -> "RATE_LIMITED ${o.scope.name.lowercase()}"
        is Outcome.Halt -> "HALT ${o.reason.key}"
        is Outcome.Transient -> "TRANSIENT"
        Outcome.Deleted, Outcome.AlreadyGone, Outcome.SystemMessage -> "HALT ${HaltReason.UNKNOWN_4XX.key}"
    }

    private fun halt(reason: HaltReason, status: Int?, code: Int?): SessionEnd {
        val notBefore = if (reason == HaltReason.EDGE_BLOCK) clock.nowMs() + edgeBlockHoldMs else null
        store.fetchPaused(reason.stopReason, notBefore)
        log("HALT ${reason.key}" + (status?.let { " $it" } ?: "") + (code?.let { " $it" } ?: ""))
        return SessionEnd.Halted(reason, status, code, notBefore)
    }

    private suspend fun wait(ms: Long, why: String) {
        if (ms <= 0) return
        log("$why $ms")
        sleeper.sleep(ms)
    }

    private fun log(action: String) = store.event(FetchEventRecord(clock.nowMs(), null, null, null, null, null, action))

    private fun dayStart(): Long = clock.localNow().toLocalDate().atStartOfDay(clock.zone).toInstant().toEpochMilli()

    /** The daily cap protects the account, so a fetch counts the day's reads and deletes together. */
    private fun requestsToday(): Int {
        val midnight = dayStart()
        return store.readsOn(midnight) + store.deletedSince(midnight)
    }

    companion object {
        const val STOP_NO_ACCOUNT_ID = "paused:no_account_id"
        const val STOP_ACCOUNT_CHANGED = "paused:account_changed"
        private const val MAX_SKIPS = 100

        /** What a fetch's `stop_reason` means for the user. Halts and pauses read as a run's do. */
        fun describe(stopReason: String?, endedAt: Long?, startedAt: Long?): String = when {
            endedAt != null && stopReason == Executor.STOP_DONE -> "Finished: every listed DM was read"
            endedAt != null && stopReason == Executor.STOP_STOPPED -> "Stopped"
            endedAt != null -> "Ended ($stopReason)"
            stopReason == null && startedAt == null -> "Not started yet"
            stopReason == null -> "Fetching, or interrupted by a kill or reboot (Resume continues it)"
            stopReason == STOP_NO_ACCOUNT_ID ->
                "Paused: Discord did not name the account these headers belong to, so nothing could be filtered and nothing was kept. Capture the headers again, then Resume"
            stopReason == STOP_ACCOUNT_CHANGED ->
                "Paused: the stored headers belong to another account than this fetch began with. Stop it and start a new fetch"
            stopReason == Executor.STOP_WAITING_APP -> "Waiting: a fetch continues only while the app is open"
            stopReason == Executor.STOP_PAUSED_GUARD -> "Paused: another worker kept reading the same step; Resume retries"
            else -> Executor.describe(stopReason, endedAt, startedAt)
        }
    }
}
