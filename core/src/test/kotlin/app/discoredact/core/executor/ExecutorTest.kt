package app.discoredact.core.executor

import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.pacing.QuietHours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/** The executor's rules (spec section 8) and the response table (section 9), with a fake store, transport and clock. */
class ExecutorTest {
    private val config = PacingConfig.forPreset(PacingPreset.SLOWER)
    private val clock = FakeClock(LocalDateTime.of(2026, 10, 21, 12, 0))
    private val sleeper = FakeSleeper(clock)
    private val store = FakeStore()
    private val transport = FakeTransport()
    private val creds = InMemoryCredentialStore(TEST_HEADERS)

    private fun executor(c: PacingConfig = config, t: Transport = transport, cs: CredentialStore = creds, tripwire: Tripwire = Tripwire()) =
        Executor(store, t, cs, c, clock, sleeper, Random(7), tripwire)

    private fun twoChannels() {
        store.channel(10, 101, 102, 103)
        store.channel(20, 201, 202)
        store.freeze(1, 101, 102, 103, 201, 202)
    }

    private fun run(e: Executor = executor(), dry: Boolean = false, oldestFirst: Boolean = false) =
        runBlocking { e.runSession(1, dry, oldestFirst) }

    @Test fun everyMessageIsDeletedOnceInRunOrderWithTheRightPacing() {
        twoChannels()
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(101L, 102L, 103L, 201L, 202L), transport.requests.map { it.messageId })
        assertEquals(listOf(10L, 10L, 10L, 20L, 20L), transport.requests.map { it.channelId })
        assertTrue(store.statuses().values.all { it == MessageStatus.DELETED })
        assertEquals(5, store.deleteLog.size)
        assertEquals(5, store.runs[1]!!.deletedCount)
        assertEquals(Executor.STOP_DONE, store.runs[1]!!.stopReason)
        assertNotNull(store.runs[1]!!.startedAt)
        assertNotNull(store.runs[1]!!.endedAt)
        // Four delays between five requests, one of them preceded by a channel-switch pause.
        val delays = sleeper.waits
        assertEquals(5, delays.size)
        val switch = delays[2]
        assertTrue("channel switch $switch", switch in config.channelSwitchMinMs..config.channelSwitchMaxMs)
        for (d in listOf(delays[0], delays[1], delays[3], delays[4])) assertTrue("delay $d", d in config.baseDelayMs..(config.baseDelayMs + config.jitterMaxMs))
        assertTrue(transport.headersSeen.all { it === TEST_HEADERS })
        assertEquals(5, store.actions().count { it == "DELETED" })
        assertTrue(store.events.all { it.httpStatus == null || it.httpStatus == 204 })
    }

    @Test fun oldestFirstReversesTheOrderWithinAChannel() {
        twoChannels()
        run(oldestFirst = true)
        assertEquals(listOf(103L, 102L, 101L, 202L, 201L), transport.requests.map { it.messageId })
    }

    @Test fun theGuardSkipsKeptAndNonPendingMessagesWithoutSending() {
        twoChannels()
        store.messages[102]!!.keep = true
        store.messages[201]!!.status = MessageStatus.ALREADY_GONE
        run()
        assertEquals(listOf(101L, 103L, 202L), transport.requests.map { it.messageId })
        assertEquals(MessageStatus.PENDING, store.messages[102]!!.status)
        assertEquals(0, store.messages[102]!!.attempts)
    }

    @Test fun keepToggledMidRunBlocksThatDelete() {
        twoChannels()
        val t = object : Transport {
            override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse {
                transport.send(request, headers)
                if (request.messageId == 101L) store.messages[102]!!.keep = true // the user stars 102 while 101 is in flight
                return Responses.ok
            }
        }
        run(executor(t = t))
        assertEquals(listOf(101L, 103L, 201L, 202L), transport.requests.map { it.messageId })
        assertEquals(MessageStatus.PENDING, store.messages[102]!!.status)
    }

    @Test fun alreadyGoneAndSystemMessagesAreMarkedAndTheRunGoesOn() {
        twoChannels()
        transport.enqueue(Responses.ok, Responses.unknownMessage, Responses.systemMessage)
        assertEquals(SessionEnd.Done, run())
        assertEquals(MessageStatus.DELETED, store.messages[101]!!.status)
        assertEquals(MessageStatus.ALREADY_GONE, store.messages[102]!!.status)
        assertEquals(10008, store.messages[102]!!.lastCode)
        assertEquals(MessageStatus.SKIPPED_SYSTEM, store.messages[103]!!.status)
        assertEquals(400, store.messages[103]!!.lastHttp)
        assertEquals(3, store.deleteLog.size)
    }

    @Test fun unknownChannelMarksItGoneAndSkipsItsRemainingMessages() {
        twoChannels()
        transport.enqueue(Responses.unknownChannel)
        assertEquals(SessionEnd.Done, run())
        assertEquals(ChannelState.GONE, store.channelStates[10])
        assertEquals(listOf(101L, 201L, 202L), transport.requests.map { it.messageId })
        assertEquals(MessageStatus.PENDING, store.messages[102]!!.status)
        assertEquals(404, store.messages[101]!!.lastHttp)
    }

    @Test fun firstForbiddenMarksTheChannelInaccessibleAndCountsTowardTheTripwire() {
        twoChannels()
        transport.enqueue(Responses.noAccess)
        val trip = Tripwire()
        assertEquals(SessionEnd.Done, run(executor(tripwire = trip)))
        assertEquals(ChannelState.INACCESSIBLE, store.channelStates[10])
        assertEquals("403 50001", store.channelErrors[10])
        assertEquals(listOf(101L, 201L, 202L), transport.requests.map { it.messageId })
        assertEquals(1, trip.count(clock.nowMs()))
    }

    @Test fun unauthorizedHaltsEverythingAndLeavesTheMessagePending() {
        twoChannels()
        transport.enqueue(Responses.ok, Responses.unauthorized)
        val end = run()
        assertEquals(SessionEnd.Halted(HaltReason.TOKEN_INVALID, 401, 0, null), end)
        assertEquals(listOf(101L, 102L), transport.requests.map { it.messageId })
        assertEquals(MessageStatus.PENDING, store.messages[102]!!.status)
        assertEquals(401, store.messages[102]!!.lastHttp)
        assertEquals("halted:401", store.runs[1]!!.stopReason)
        assertNull(store.runs[1]!!.endedAt)
    }

    @Test fun captchaHaltsAndIsNeverSolved() {
        twoChannels()
        transport.enqueue(Responses.captcha)
        assertEquals(HaltReason.CAPTCHA, (run() as SessionEnd.Halted).reason)
        assertEquals(1, transport.requests.size)
        assertEquals("halted:captcha", store.runs[1]!!.stopReason)
    }

    @Test fun edgeBlockHaltsForAtLeastAnHour() {
        twoChannels()
        transport.enqueue(Responses.html)
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.EDGE_BLOCK, end.reason)
        assertEquals(clock.nowMs() + 3_600_000, end.notBeforeMs)
        assertEquals(end.notBeforeMs, store.runs[1]!!.notBefore)
        assertEquals("halted:edge", store.runs[1]!!.stopReason)
    }

    @Test fun unknownFourHundredHaltsWithStatusAndCodeAndMarksTheMessageFailed() {
        twoChannels()
        transport.enqueue(Responses.json(403, """{"code": 40002, "message": "You need to verify your account"}"""))
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.UNKNOWN_4XX, end.reason); assertEquals(403, end.status); assertEquals(40002, end.code)
        assertEquals(MessageStatus.FAILED, store.messages[101]!!.status)
        assertTrue(store.actions().any { it == "HALT unknown_4xx 403 40002" })
    }

    @Test fun sharedRateLimitWaitsAndIsNotCountedNorDoubled() {
        twoChannels()
        transport.enqueue(Responses.rateLimited("shared", 2.0))
        val trip = Tripwire()
        assertEquals(SessionEnd.Done, run(executor(tripwire = trip)))
        // 101 was retried after the wait: six requests for five messages.
        assertEquals(listOf(101L, 101L, 102L, 103L, 201L, 202L), transport.requests.map { it.messageId })
        val i = store.actions().indexOfFirst { it.startsWith("RATE_LIMIT_WAIT") }
        val waited = store.actions()[i].substringAfter(' ').toLong()
        assertTrue("wait $waited", waited in 2_250L..2_750L)
        assertEquals(0, trip.count(clock.nowMs()))
        val afterWait = sleeper.waits.filter { it in config.baseDelayMs..(config.baseDelayMs + config.jitterMaxMs) }
        assertEquals(5, afterWait.size) // no doubling
    }

    @Test fun userRateLimitWaitsDoublesTheDelayForTheSessionAndCounts() {
        twoChannels()
        transport.enqueue(Responses.rateLimited("user", 1.0))
        val trip = Tripwire()
        assertEquals(SessionEnd.Done, run(executor(tripwire = trip)))
        assertEquals(1, trip.count(clock.nowMs()))
        val delays = sleeper.waits.filter { it !in config.channelSwitchMinMs..config.channelSwitchMaxMs }
        // the first delay (before the retry) is still single; the rate-limit wait is 1,000 + margin; after that doubled
        val doubled = delays.filter { it in (2 * config.baseDelayMs)..(2 * (config.baseDelayMs + config.jitterMaxMs)) }
        assertTrue("doubled delays ${delays}", doubled.size >= 4)
        assertTrue(store.actions().any { it == "RATE_LIMITED user" })
    }

    @Test fun theTripwireHaltsAtTheFifthInvalidResponseWithinTenMinutes() {
        store.channel(10, 1, 2, 3, 4, 5, 6, 7)
        store.freeze(1, 1, 2, 3, 4, 5, 6, 7)
        repeat(5) { transport.enqueue(Responses.rateLimited("user", 0.5)) }
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TRIPWIRE, end.reason)
        assertEquals(5, transport.requests.size)
        assertEquals("halted:tripwire", store.runs[1]!!.stopReason)
    }

    @Test fun fourInvalidResponsesDoNotTripIt() {
        store.channel(10, 1, 2, 3, 4, 5)
        store.freeze(1, 1, 2, 3, 4, 5)
        repeat(4) { transport.enqueue(Responses.rateLimited("user", 0.5)) }
        assertEquals(SessionEnd.Done, run())
        assertEquals(9, transport.requests.size)
    }

    @Test fun remainingOneMeansWaitingForTheResetBeforeTheNextRequest() {
        twoChannels()
        transport.enqueue(Responses.okWithLimit(1, 4.0), Responses.okWithLimit(0, 3.0), Responses.okWithLimit(4, 2.0))
        assertEquals(SessionEnd.Done, run())
        val resets = store.actions().filter { it.startsWith("WAIT_RESET") }.map { it.substringAfter(' ').toLong() }
        assertEquals(2, resets.size)
        assertTrue(resets[0] in 4_250L..4_750L)
        assertTrue(resets[1] in 3_250L..3_750L)
        val e = store.events.first { it.httpStatus == 204 && it.remaining == 1 }
        assertEquals("abc", e.bucket); assertEquals(4.0, e.resetAfter!!, 1e-9)
    }

    @Test fun transientFailuresBackOffThenPauseTheRun() {
        twoChannels()
        transport.enqueue(Responses.serverError, TransportException("connect timed out"), Responses.emptyServerError, Responses.serverError)
        val e = executor()
        assertEquals(SessionEnd.PausedTransient, run(e))
        assertEquals(4, transport.requests.size)
        assertEquals(listOf(30_000L, 120_000L, 600_000L), store.actions().filter { it.startsWith("TRANSIENT_BACKOFF") }.map { it.substringAfter(' ').toLong() })
        assertEquals("paused:transient", store.runs[1]!!.stopReason)
        assertNull(store.runs[1]!!.endedAt)
        assertEquals(MessageStatus.PENDING, store.messages[101]!!.status)
        assertEquals(4, store.messages[101]!!.attempts)
        assertTrue(store.actions().contains("NO_RESPONSE"))
    }

    @Test fun aSuccessResetsTheTransientCount() {
        twoChannels()
        transport.enqueue(Responses.serverError, Responses.serverError, Responses.ok, Responses.serverError, Responses.serverError, Responses.serverError)
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(30_000L, 120_000L, 30_000L, 120_000L, 600_000L), store.actions().filter { it.startsWith("TRANSIENT_BACKOFF") }.map { it.substringAfter(' ').toLong() })
    }

    @Test fun theSessionEndsAfterItsLengthWithABreak() {
        store.channel(10, *LongArray(2_000) { it + 1L })
        store.freeze(1, *LongArray(2_000) { it + 1L })
        val end = run()
        assertTrue(end is SessionEnd.Break)
        val b = (end as SessionEnd.Break).breakMs
        assertTrue(b in config.breakMinMs..config.breakMaxMs)
        val elapsed = clock.nowMs() - store.runs[1]!!.startedAt!!
        assertTrue("elapsed $elapsed", elapsed >= config.sessionMaxMs && elapsed < config.sessionMaxMs + 7_000)
        assertNull(store.runs[1]!!.endedAt)
    }

    @Test fun quietHoursEndTheSessionAtOneAndNotBefore() {
        twoChannels()
        clock.now = LocalDateTime.of(2026, 10, 22, 0, 59, 59).atZone(clock.zone).toInstant().toEpochMilli()
        val e = executor()
        // First request at 00:59:59 is allowed; the delay after it crosses 01:00 and the next check ends the session.
        val end = run(e)
        assertTrue("$end", end is SessionEnd.QuietUntil)
        assertEquals(1, transport.requests.size)
        val until = (end as SessionEnd.QuietUntil).untilMs
        assertEquals(LocalDateTime.of(2026, 10, 22, 8, 0).atZone(clock.zone).toInstant().toEpochMilli(), until)
    }

    @Test fun eightOClockIsNotQuietAndNoQuietHoursMeansNever() {
        twoChannels()
        clock.now = LocalDateTime.of(2026, 10, 22, 8, 0).atZone(clock.zone).toInstant().toEpochMilli()
        assertEquals(SessionEnd.Done, run())
        val store2 = FakeStore().also { it.channel(1, 5); it.freeze(1, 5) }
        clock.now = LocalDateTime.of(2026, 10, 22, 3, 0).atZone(clock.zone).toInstant().toEpochMilli()
        val e = Executor(store2, transport, creds, config.copy(quietHours = QuietHours(0, 0)), clock, sleeper, Random(1))
        assertEquals(SessionEnd.Done, runBlocking { e.runSession(1, false, false) })
    }

    @Test fun theDailyCapEndsTheSessionUntilMidnightAndCountsAcrossRuns() {
        twoChannels()
        repeat(999) { store.deleteLog.add(900L + it to clock.nowMs() - 60_000) } // earlier today, another run
        val end = run()
        assertEquals(1, transport.requests.size)
        assertTrue(end is SessionEnd.CapUntil)
        assertEquals(LocalDateTime.of(2026, 10, 22, 0, 0).atZone(clock.zone).toInstant().toEpochMilli(), (end as SessionEnd.CapUntil).untilMs)
        // Yesterday's deletes do not count.
        val store2 = FakeStore().also { it.channel(1, 5, 6); it.freeze(1, 5, 6) }
        repeat(1_000) { store2.deleteLog.add(it.toLong() to clock.nowMs() - 13 * 3_600_000) }
        val e = Executor(store2, FakeTransport(), creds, config, clock, sleeper, Random(1))
        assertEquals(SessionEnd.Done, runBlocking { e.runSession(1, false, false) })
    }

    @Test fun dryRunSendsNothingNeedsNoCredentialsAndReportsVerdicts() {
        twoChannels()
        store.messages[103]!!.keep = true
        store.messages[202]!!.status = MessageStatus.DELETED
        val e = executor(t = object : Transport {
            override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse = throw AssertionError("a dry run must not send")
        }, cs = EmptyCredentialStore)
        assertEquals(SessionEnd.Done, run(e, dry = true))
        // Kept and non-pending messages never reach the guard (the candidate query excludes them); the report derives
        // their skips from the frozen set, so the verdicts hold only what a real run would send.
        assertEquals(mapOf(101L to DryVerdict.WOULD_DELETE, 102L to DryVerdict.WOULD_DELETE, 201L to DryVerdict.WOULD_DELETE), store.verdicts)
        assertTrue(store.deleteLog.isEmpty())
        assertEquals(MessageStatus.PENDING, store.messages[101]!!.status)
        assertEquals(Executor.STOP_DONE, store.runs[1]!!.stopReason)
        assertEquals(3, store.actions().count { it == "DRY_WOULD_DELETE" })
        assertTrue("a dry run never waits", sleeper.waits.isEmpty())
    }

    @Test fun aDryRunOfAKeptOnlySetIsDoneAndADryRunIgnoresQuietHours() {
        twoChannels()
        clock.now = LocalDateTime.of(2026, 10, 22, 3, 0).atZone(clock.zone).toInstant().toEpochMilli()
        assertEquals(SessionEnd.Done, run(executor(cs = EmptyCredentialStore), dry = true))
        assertEquals(5, store.verdicts.size)
    }

    @Test fun aDryRunInterruptedHalfWayResumesWithoutRepeatingVerdicts() {
        twoChannels()
        val stopAfterTwo = object : RunStore by store {
            override fun dryVerdict(runId: Long, messageId: Long, verdict: DryVerdict) {
                store.dryVerdict(runId, messageId, verdict)
                if (store.verdicts.size == 2) throw CancellationException("killed")
            }
        }
        val e = Executor(stopAfterTwo, transport, EmptyCredentialStore, config, clock, sleeper, Random(1))
        try { runBlocking { e.runSession(1, true, false) }; throw AssertionError() } catch (_: CancellationException) {}
        assertEquals(SessionEnd.Done, run(executor(cs = EmptyCredentialStore), dry = true))
        assertEquals(listOf(101L, 102L, 103L, 201L, 202L), store.verdicts.keys.toList())
        assertTrue(store.verdicts.values.all { it == DryVerdict.WOULD_DELETE })
    }

    @Test fun aRealRunWithoutCredentialsSendsNothing() {
        twoChannels()
        assertEquals(SessionEnd.NoCredentials, run(executor(cs = EmptyCredentialStore)))
        assertTrue(transport.requests.isEmpty())
        assertEquals("paused:no_credentials", store.runs[1]!!.stopReason)
    }

    @Test fun aPausedSessionResumesWithNoRepeatAndNoSkip() {
        twoChannels()
        sleeper.cancelAfterWaits = 1 // the wait before the third request is cancelled (Pause)
        try {
            run(); throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf(101L, 102L), transport.requests.map { it.messageId })
        assertEquals(MessageStatus.DELETED, store.messages[102]!!.status)
        assertNull(store.runs[1]!!.endedAt)
        // Resume: a new session, as after a kill or reboot.
        sleeper.cancelAfterWaits = Int.MAX_VALUE
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(101L, 102L, 103L, 201L, 202L), transport.requests.map { it.messageId })
        assertEquals(5, transport.requests.map { it.messageId }.toSet().size)
    }

    @Test fun stopDuringAWaitEndsTheSessionWithoutAnotherRequest() {
        twoChannels()
        val stopping = object : Sleeper {
            override suspend fun sleep(ms: Long) {
                sleeper.sleep(ms)
                store.runEnded(1, clock.nowMs(), Executor.STOP_STOPPED)
            }
        }
        val e = Executor(store, transport, creds, config, clock, stopping, Random(3))
        assertEquals(SessionEnd.Stopped, run(e))
        assertEquals(1, transport.requests.size)
        // and an ended run never starts a session
        assertEquals(SessionEnd.Stopped, run(executor()))
        assertEquals(1, transport.requests.size)
    }

    @Test fun anInterruptedRunResumesWithItsStartTimeKept() {
        twoChannels()
        store.runs[1]!!.startedAt = 5L
        store.runs[1]!!.stopReason = "halted:401"
        run()
        assertEquals(5L, store.runs[1]!!.startedAt)
        assertEquals(Executor.STOP_DONE, store.runs[1]!!.stopReason)
    }

    @Test fun noEventEverCarriesAHeaderValue() {
        twoChannels()
        transport.enqueue(Responses.unauthorized, Responses.captcha, Responses.html, Responses.rateLimited("user", 1.0), Responses.serverError)
        repeat(5) { runCatching { run() }; store.runs[1]!!.stopReason = null }
        val text = store.events.joinToString("\n") { listOf(it.actionTaken, it.bucket, it.httpStatus, it.discordCode, it.remaining, it.resetAfter).joinToString(" ") }
        assertFalse(text.contains("TOKEN-SENTINEL"))
        assertFalse(text.contains(TEST_HEADERS.userAgent))
        assertFalse(text.contains(TEST_HEADERS.superProperties))
        assertFalse(TEST_HEADERS.toString().contains("TOKEN-SENTINEL"))
    }

    @Test fun keepToggledDuringTheWaitBeforeARequestBlocksIt() {
        twoChannels()
        // The user stars 102 while the executor is waiting out the delay before sending it.
        val starring = object : Sleeper {
            override suspend fun sleep(ms: Long) {
                sleeper.sleep(ms)
                if (transport.requests.size == 1) store.messages[102]!!.keep = true
            }
        }
        val e = Executor(store, transport, creds, config, clock, starring, Random(3))
        assertEquals(SessionEnd.Done, run(e))
        assertEquals(listOf(101L, 103L, 201L, 202L), transport.requests.map { it.messageId })
        assertEquals(MessageStatus.PENDING, store.messages[102]!!.status)
        assertTrue(store.actions().contains("GUARD_SKIP"))
    }

    @Test fun twoExecutorsOnOneLockNeverBothSendTheSameMessage() {
        twoChannels()
        val lock = kotlinx.coroutines.sync.Mutex()
        val slow = object : Transport {
            override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse {
                Thread.sleep(30)
                return transport.send(request, headers)
            }
        }
        val a = Executor(store, slow, creds, config.copy(baseDelayMs = 0, jitterMaxMs = 0, channelSwitchMinMs = 0, channelSwitchMaxMs = 0), clock, Sleeper {}, Random(1), sendMutex = lock)
        val b = Executor(store, slow, creds, config.copy(baseDelayMs = 0, jitterMaxMs = 0, channelSwitchMinMs = 0, channelSwitchMaxMs = 0), clock, Sleeper {}, Random(2), sendMutex = lock)
        runBlocking {
            val ja = this.launch(kotlinx.coroutines.Dispatchers.Default) { a.runSession(1, false, false) }
            val jb = this.launch(kotlinx.coroutines.Dispatchers.Default) { b.runSession(1, false, false) }
            ja.join(); jb.join()
        }
        assertEquals(5, transport.requests.size)
        assertEquals(5, transport.requests.map { it.messageId }.toSet().size)
        assertTrue(store.statuses().values.all { it == MessageStatus.DELETED })
    }

    @Test fun theTripwireIsSeededFromTheEventLogAcrossSessions() {
        store.channel(10, 1, 2, 3, 4, 5, 6)
        store.freeze(1, 1, 2, 3, 4, 5, 6)
        // Four invalid responses logged by an earlier session, three minutes ago.
        repeat(4) { store.events.add(EventRecord(clock.nowMs() - 180_000, 1, 429, null, null, null, null, "RATE_LIMITED user")) }
        transport.enqueue(Responses.rateLimited("user", 0.5))
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TRIPWIRE, end.reason)
        assertEquals(1, transport.requests.size)
        // Shared-scope limits logged earlier do not count.
        val store2 = FakeStore().also { it.channel(1, 7, 8); it.freeze(1, 7, 8) }
        repeat(10) { store2.events.add(EventRecord(clock.nowMs() - 60_000, 1, 429, null, null, null, null, "RATE_LIMITED shared")) }
        val e = Executor(store2, FakeTransport(), creds, config, clock, sleeper, Random(1))
        assertEquals(SessionEnd.Done, runBlocking { e.runSession(1, false, false) })
    }

    @Test fun aDryRunCanBePaused() {
        store.channel(10, *LongArray(50) { it + 1L })
        store.freeze(1, *LongArray(50) { it + 1L })
        val e = executor(cs = EmptyCredentialStore)
        runBlocking {
            val job = launch {
                e.runSession(1, true, false)
            }
            while (store.verdicts.size < 3) kotlinx.coroutines.yield()
            job.cancel()
            job.join()
        }
        assertTrue("stopped early: ${store.verdicts.size}", store.verdicts.size < 50)
        assertNull(store.runs[1]!!.endedAt)
    }
}
