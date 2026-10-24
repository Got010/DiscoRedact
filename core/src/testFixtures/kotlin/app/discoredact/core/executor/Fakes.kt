package app.discoredact.core.executor

import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.selection.GuardRow
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests move by hand. */
class FakeClock(start: LocalDateTime, override val zone: ZoneId = ZoneOffset.UTC) : Clock {
    var now: Long = start.atZone(zone).toInstant().toEpochMilli()
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
    fun at(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
}

/** Sleeps by advancing the fake clock; records every wait. Can be told to throw (a Pause cancels the wait). */
class FakeSleeper(private val clock: FakeClock) : Sleeper {
    val waits = ArrayList<Long>()
    var cancelAfterWaits: Int = Int.MAX_VALUE
    override suspend fun sleep(ms: Long) {
        waits.add(ms)
        if (waits.size > cancelAfterWaits) throw CancellationException("paused")
        clock.advance(ms)
    }
}

/**
 * Scripted responses in order; records every request and every header set it was given. Deletes and reads share the
 * script; [inFlight] and [maxInFlight] count overlapping calls (never more than one).
 */
class FakeTransport(vararg script: Any) : Transport {
    private val queue = ArrayDeque<Any>(script.toList())
    val requests = ArrayList<DeleteRequest>()
    val reads = ArrayList<GetRequest>()
    val headersSeen = ArrayList<HeaderSet>()
    var default: Any = ApiResponse(204, emptyMap(), "")
    /** A read's answer when the script is empty; null means [default]. */
    var readDefault: ((GetRequest) -> Any)? = null
    @Volatile var inFlight = 0
    @Volatile var maxInFlight = 0
    fun enqueue(vararg r: Any) = queue.addAll(r.toList())

    override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse {
        requests.add(request)
        headersSeen.add(headers)
        val next = queue.removeFirstOrNull() ?: default
        if (next is Throwable) throw next
        return next as ApiResponse
    }

    override fun get(request: GetRequest, headers: HeaderSet): ApiResponse {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            reads.add(request)
            headersSeen.add(headers)
            val next = queue.removeFirstOrNull() ?: readDefault?.invoke(request) ?: default
            if (next is Throwable) throw next
            return next as ApiResponse
        } finally {
            inFlight--
        }
    }
}

class FakeStore : RunStore {
    class Msg(val id: Long, val channelId: Long, var status: MessageStatus = MessageStatus.PENDING, var keep: Boolean = false,
              var attempts: Int = 0, var lastHttp: Int? = null, var lastCode: Int? = null, var updatedAt: Long = 0)
    class Run(var startedAt: Long? = null, var endedAt: Long? = null, var stopReason: String? = null, var notBefore: Long? = null, var deletedCount: Int = 0)

    val messages = LinkedHashMap<Long, Msg>()
    val channelStates = HashMap<Long, ChannelState>()
    val channelErrors = HashMap<Long, String?>()
    /** run id -> frozen set in run order */
    val runSets = HashMap<Long, List<Long>>()
    val runs = HashMap<Long, Run>()
    val events = ArrayList<EventRecord>()
    val deleteLog = ArrayList<Pair<Long, Long>>()
    val verdicts = LinkedHashMap<Long, DryVerdict>()

    fun channel(id: Long, vararg messageIds: Long) {
        channelStates[id] = ChannelState.PENDING
        messageIds.forEach { messages[it] = Msg(it, id) }
    }

    fun freeze(runId: Long, vararg ids: Long) {
        runSets[runId] = ids.toList()
        runs[runId] = Run()
    }

    override fun nextCandidate(runId: Long, oldestFirst: Boolean, dryRun: Boolean): Candidate? {
        val set = runSets[runId] ?: return null
        val ordered = if (!oldestFirst) set else {
            // reverse within each channel, channels in first-appearance order
            set.groupBy { messages[it]!!.channelId }.values.flatMap { it.reversed() }
        }
        for ((i, id) in ordered.withIndex()) {
            val m = messages[id] ?: continue
            if (dryRun && id in verdicts) continue
            if (m.status != MessageStatus.PENDING || m.keep) continue
            val cs = channelStates[m.channelId]
            if (cs == ChannelState.GONE || cs == ChannelState.INACCESSIBLE) continue
            return Candidate(id, m.channelId, i)
        }
        return null
    }

    override fun guardRow(messageId: Long): GuardRow? = messages[messageId]?.let { GuardRow(it.id, it.status, it.keep) }
    override fun inRun(runId: Long, messageId: Long): Boolean = runSets[runId]?.contains(messageId) == true

    override fun messageOutcome(messageId: Long, status: MessageStatus, httpStatus: Int?, discordCode: Int?, now: Long) {
        val m = messages.getValue(messageId)
        m.status = status; m.lastHttp = httpStatus; m.lastCode = discordCode; m.attempts++; m.updatedAt = now
    }

    override fun channelState(channelId: Long, state: ChannelState, error: String?) {
        channelStates[channelId] = state; channelErrors[channelId] = error
    }

    override fun deleted(runId: Long, messageId: Long, now: Long) {
        deleteLog.add(messageId to now); runs.getValue(runId).deletedCount++
    }

    override fun dryVerdict(runId: Long, messageId: Long, verdict: DryVerdict) { verdicts[messageId] = verdict }
    override fun event(event: EventRecord) { events.add(event) }
    override fun deletedSince(sinceMs: Long): Int = deleteLog.count { it.second >= sinceMs }
    override fun invalidResponseTimesSince(sinceMs: Long): List<Long> = events.filter {
        it.ts >= sinceMs && (it.httpStatus == 401 || it.httpStatus == 403 || (it.httpStatus == 429 && it.actionTaken != "RATE_LIMITED shared"))
    }.map { it.ts }
    override fun run(runId: Long): RunSnapshot? = runs[runId]?.let { RunSnapshot(it.startedAt, it.endedAt, it.stopReason) }
    override fun runStarted(runId: Long, now: Long) {
        val r = runs.getValue(runId); if (r.startedAt == null) r.startedAt = now; r.stopReason = null; r.notBefore = null
    }
    override fun runEnded(runId: Long, now: Long, reason: String) { val r = runs.getValue(runId); r.endedAt = now; r.stopReason = reason }
    override fun runPaused(runId: Long, reason: String, resumeNotBefore: Long?) { val r = runs.getValue(runId); r.stopReason = reason; r.notBefore = resumeNotBefore }

    fun actions(): List<String> = events.map { it.actionTaken }
    fun statuses(): Map<Long, MessageStatus> = messages.mapValues { it.value.status }
}

object Responses {
    fun json(status: Int, body: String, vararg headers: Pair<String, String>) = ApiResponse(status, headers.toMap().mapKeys { it.key.lowercase() }, body)
    val ok = ApiResponse(204, emptyMap(), "")
    val unknownMessage = json(404, """{"message": "Unknown Message", "code": 10008}""")
    val unknownChannel = json(404, """{"message": "Unknown Channel", "code": 10003}""")
    val noAccess = json(403, """{"message": "Missing Access", "code": 50001}""")
    val missingPermissions = json(403, """{"message": "Missing Permissions", "code": 50013}""")
    val systemMessage = json(400, """{"message": "Cannot delete a message authored by another user", "code": 50021}""")
    val unauthorized = json(401, """{"message": "401: Unauthorized", "code": 0}""")
    val captcha = json(400, """{"captcha_key": ["captcha-required"], "captcha_sitekey": "x", "captcha_service": "hcaptcha"}""")
    val html = json(503, "<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>cloudflare</body></html>", "content-type" to "text/html")
    val serverError = json(502, """{"message": "502: Bad Gateway", "code": 0}""")
    val emptyServerError = ApiResponse(500, emptyMap(), "")
    fun rateLimited(scope: String, retryAfter: Double, global: Boolean = false) = json(
        429,
        """{"message": "You are being rate limited.", "retry_after": $retryAfter, "global": $global}""",
        "x-ratelimit-scope" to scope, "retry-after" to Math.ceil(retryAfter).toInt().toString(),
    )
    fun okWithLimit(remaining: Int, resetAfter: Double, bucket: String = "abc") =
        json(204, "", "x-ratelimit-remaining" to remaining.toString(), "x-ratelimit-reset-after" to resetAfter.toString(), "x-ratelimit-bucket" to bucket, "x-ratelimit-limit" to "5")
}

val TEST_HEADERS = HeaderSet(
    authorization = "TOKEN-SENTINEL-1234", userAgent = "Mozilla/5.0 (test)", superProperties = "eyJ0ZXN0IjoxfQ==",
    locale = "en-US", timezone = "America/Chicago", capturedAt = 0L,
)
