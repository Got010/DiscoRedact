package app.discoredact.core.history

import app.discoredact.core.executor.ApiResponse
import app.discoredact.core.executor.CredentialStore
import app.discoredact.core.executor.DeleteRequest
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.FakeClock
import app.discoredact.core.executor.FakeSleeper
import app.discoredact.core.executor.FakeTransport
import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.HeaderSet
import app.discoredact.core.executor.InMemoryCredentialStore
import app.discoredact.core.executor.Responses
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.Sleeper
import app.discoredact.core.executor.TEST_HEADERS
import app.discoredact.core.executor.Transport
import app.discoredact.core.executor.TransportException
import app.discoredact.core.executor.Tripwire
import app.discoredact.core.history.Pages.ME
import app.discoredact.core.history.Pages.OTHER
import app.discoredact.core.history.Pages.channels
import app.discoredact.core.history.Pages.dm
import app.discoredact.core.history.Pages.me
import app.discoredact.core.history.Pages.msg
import app.discoredact.core.history.Pages.page
import app.discoredact.core.history.Pages.pageRows
import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.importer.ImportedMessage
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.RowSource
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.pacing.QuietHours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** The read-side executor (B34 API history, sections 3 and 4) with a fake store, transport and clock. */
class FetchSessionTest {
    private val config = PacingConfig.forPreset(PacingPreset.SLOWER)
    private val clock = FakeClock(LocalDateTime.of(2026, 10, 24, 12, 0))
    private val sleeper = FakeSleeper(clock)
    private val store = FakeFetchStore()
    private val transport = FakeTransport()
    private val creds = InMemoryCredentialStore(TEST_HEADERS)
    private val day = LocalDateTime.of(2026, 10, 24, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun session(c: PacingConfig = config, t: Transport = transport, cs: CredentialStore = creds, tripwire: Tripwire = Tripwire()) =
        FetchSession(store, t, cs, c, clock, sleeper, Random(7), tripwire)

    private fun run(s: FetchSession = session()) = runBlocking { s.runSession() }

    /** A fetch that already knows the account and its two DMs. */
    private fun listed(vararg dms: Long) {
        store.fetch!!.accountId = ME
        store.fetch!!.capturedAt = TEST_HEADERS.capturedAt
        store.fetch!!.listedAt = 1
        store.channelsSeen(dms.map { ImportedChannel(it, ChannelType.DM, "dm $it", emptyList()) }, 1)
        store.writes.clear()
    }

    @Test fun aFetchReadsTheIdTheListThenEachDmPageByPage() {
        transport.enqueue(
            me(), channels(dm(10, 501L to "anna"), dm(20, 502L to "ben"), """{"id":"30","type":0,"guild_id":"1"}"""),
            page(pageRows(10, 5_000, 100)), page(pageRows(10, 4_900, 30)), page(emptyList()),
        )
        assertEquals(SessionEnd.Done, run())
        assertEquals(
            listOf(GetRequest.Me, GetRequest.MyChannels, GetRequest.Messages(10, null), GetRequest.Messages(10, 4_901), GetRequest.Messages(20, null)),
            transport.reads,
        )
        assertTrue(transport.requests.isEmpty())
        assertEquals(65, store.own(10).size)
        assertEquals(0, store.own(20).size)
        assertTrue(store.messages.values.all { it.source == RowSource.API })
        assertEquals(setOf(10L, 20L), store.channels.keys)
        assertEquals("anna", store.channels[10]!!.channel.displayName)
        assertEquals(listOf(FetchProgress(10, 4_871, true), FetchProgress(20, null, true)), store.progress.values.toList())
        assertEquals(Executor.STOP_DONE, store.fetch!!.stopReason)
        assertNotNull(store.fetch!!.endedAt)
        assertEquals(ME, store.fetch!!.accountId)
        assertEquals(5, store.readsOn(day))
        // Four request delays and one channel-switch pause, before the first read of the second DM.
        assertEquals(5, sleeper.waits.size)
        assertTrue(sleeper.waits[3] in config.channelSwitchMinMs..config.channelSwitchMaxMs)
        for (i in listOf(0, 1, 2, 4)) assertTrue(sleeper.waits[i] in config.baseDelayMs..(config.baseDelayMs + config.jitterMaxMs))
        assertTrue(transport.headersSeen.all { it === TEST_HEADERS })
        assertEquals(listOf("FETCH_ME", "FETCH_CHANNELS 2 skipped 1", "FETCH_PAGE 100 own 50", "FETCH_PAGE 30 own 15 done", "FETCH_PAGE 0 own 0 done"), store.events.filter { it.httpStatus != null }.map { it.actionTaken })
        assertEquals(listOf("channels", "page 10", "page 10", "page 20"), store.writes)
    }

    @Test fun noOtherAuthorsRowEverReachesTheStore() {
        transport.enqueue(me(), channels(dm(10)), page(listOf(msg(103, OTHER, 10), msg(102, ME, 10), msg(101, 12345, 10))))
        run()
        assertEquals(listOf(102L), store.messages.keys.toList())
    }

    @Test fun aKillBetweenPagesReadsNothingTwice() {
        transport.enqueue(me(), channels(dm(10), dm(20)), page(pageRows(10, 5_000, 100)))
        sleeper.cancelAfterWaits = 2 // the delay before the second page of DM 10 is where the process dies
        try {
            run(); fail("expected the kill")
        } catch (_: CancellationException) {
        }
        assertEquals(3, transport.reads.size)
        assertEquals(FetchProgress(10, 4_901, false), store.progress[10])
        // The next process: no id, no list and no page again, only what is left.
        sleeper.cancelAfterWaits = Int.MAX_VALUE
        transport.reads.clear()
        transport.enqueue(page(pageRows(10, 4_900, 10)), page(emptyList()))
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(GetRequest.Messages(10, 4_901), GetRequest.Messages(20, null)), transport.reads)
        assertEquals(55, store.own(10).size)
    }

    @Test fun aKillBetweenAResponseAndItsRowsReadsThatPageAgainAndSkipsNone() {
        listed(10)
        transport.enqueue(page(pageRows(10, 5_000, 100)))
        store.failNextPage = true
        try {
            run(); fail("expected the kill")
        } catch (e: IllegalStateException) {
            assertEquals("killed", e.message)
        }
        assertEquals(FetchProgress(10, null, false), store.progress[10])
        assertTrue(store.messages.isEmpty())
        transport.reads.clear()
        transport.enqueue(page(pageRows(10, 5_000, 100)), page(pageRows(10, 4_900, 1)))
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(GetRequest.Messages(10, null), GetRequest.Messages(10, 4_901)), transport.reads)
        assertEquals(51, store.own(10).size)
    }

    @Test fun doneDmsAreSkipped() {
        listed(10, 20, 30)
        store.pageDone(10, 77, true, 1)
        store.pageDone(20, 500, false, 1)
        transport.enqueue(page(pageRows(20, 499, 5)), page(emptyList()))
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(GetRequest.Messages(20, 500), GetRequest.Messages(30, null)), transport.reads)
    }

    @Test fun anIdMissingFromUsersMeRefusesToStartAndReadsNothingElse() {
        transport.enqueue(me(null))
        assertEquals(SessionEnd.NoAccountId, run())
        assertEquals(listOf<GetRequest>(GetRequest.Me), transport.reads)
        assertEquals(FetchSession.STOP_NO_ACCOUNT_ID, store.fetch!!.stopReason)
        assertNull(store.fetch!!.accountId)
        assertTrue(store.channels.isEmpty() && store.messages.isEmpty())
    }

    @Test fun anotherCaptureAsksForTheIdAgainAndRefusesAnotherAccount() {
        listed(10)
        val recaptured = InMemoryCredentialStore(HeaderSet("TOKEN-OTHER", "UA", "e30=", "en-US", "UTC", capturedAt = 5))
        transport.enqueue(me(OTHER))
        assertEquals(SessionEnd.AccountChanged, run(session(cs = recaptured)))
        assertEquals(listOf<GetRequest>(GetRequest.Me), transport.reads)
        assertEquals(FetchSession.STOP_ACCOUNT_CHANGED, store.fetch!!.stopReason)
        assertEquals(ME, store.fetch!!.accountId)
        // The same account under a new capture continues where it was.
        transport.reads.clear()
        transport.enqueue(me(ME), page(emptyList()))
        assertEquals(SessionEnd.Done, run(session(cs = recaptured)))
        assertEquals(listOf(GetRequest.Me, GetRequest.Messages(10, null)), transport.reads)
        assertEquals(5L, store.fetch!!.capturedAt)
    }

    @Test fun noCredentialsSendsNothing() {
        assertEquals(SessionEnd.NoCredentials, run(session(cs = InMemoryCredentialStore(null))))
        assertTrue(transport.reads.isEmpty())
        assertEquals(Executor.STOP_NO_CREDENTIALS, store.fetch!!.stopReason)
    }

    @Test fun aStoppedOrMissingFetchSendsNothing() {
        store.fetch!!.endedAt = 1
        assertEquals(SessionEnd.Stopped, run())
        store.fetch = null
        assertEquals(SessionEnd.Stopped, run())
        assertTrue(transport.reads.isEmpty())
    }

    @Test fun unauthorizedHaltsAndCounts() {
        listed(10)
        transport.enqueue(Responses.unauthorized)
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TOKEN_INVALID, end.reason)
        assertEquals("halted:401", store.fetch!!.stopReason)
        assertEquals(FetchProgress(10, null, false), store.progress[10])
        assertEquals(1, store.invalidResponseTimesSince(0).size)
    }

    @Test fun aCaptchaHalts() {
        listed(10)
        transport.enqueue(Responses.captcha)
        assertEquals(HaltReason.CAPTCHA, (run() as SessionEnd.Halted).reason)
        assertTrue(store.messages.isEmpty())
    }

    @Test fun anHtmlBodyHaltsForAnHourWhateverTheStatus() {
        listed(10)
        transport.enqueue(Responses.html)
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.EDGE_BLOCK, end.reason)
        assertEquals(clock.now + 60 * 60 * 1000L, end.notBeforeMs)
        assertEquals(end.notBeforeMs, store.fetch!!.notBefore)
        store.fetch!!.stopReason = null
        transport.enqueue(Pages.ok("<!DOCTYPE html><html>cloudflare</html>"))
        assertEquals(HaltReason.EDGE_BLOCK, (run() as SessionEnd.Halted).reason)
        assertTrue(store.messages.isEmpty())
        assertEquals(FetchProgress(10, null, false), store.progress[10])
    }

    @Test fun aPageOfAnotherShapeHaltsAndWritesNothing() {
        listed(10)
        transport.enqueue(Pages.ok("""{"message":"x","code":0}"""))
        assertEquals(HaltReason.UNEXPECTED_BODY, (run() as SessionEnd.Halted).reason)
        store.fetch!!.stopReason = null
        transport.enqueue(Pages.ok(""))
        assertEquals(HaltReason.UNEXPECTED_BODY, (run() as SessionEnd.Halted).reason)
        // A full page whose ids do not move back would repeat forever: halted instead.
        store.fetch!!.stopReason = null
        store.pageDone(10, 4_000, false, 1)
        transport.enqueue(page(pageRows(10, 5_000, 100)))
        assertEquals(HaltReason.UNEXPECTED_BODY, (run() as SessionEnd.Halted).reason)
        assertTrue(store.messages.isEmpty())
    }

    @Test fun aSharedRateLimitWaitsAndIsNotCounted() {
        listed(10)
        transport.enqueue(Responses.rateLimited("shared", 2.0), page(emptyList()))
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(GetRequest.Messages(10, null), GetRequest.Messages(10, null)), transport.reads)
        val wait = sleeper.waits.last()
        assertTrue("wait $wait", wait >= 2_000 + 250)
        assertTrue(store.invalidResponseTimesSince(0).isEmpty())
        assertTrue(store.actions().contains("RATE_LIMITED shared"))
    }

    @Test fun aUserRateLimitWaitsDoublesTheDelayAndCounts() {
        listed(10)
        transport.enqueue(Responses.rateLimited("user", 1.0), page(pageRows(10, 5_000, 100)), page(emptyList()))
        assertEquals(SessionEnd.Done, run())
        assertEquals(1, store.invalidResponseTimesSince(0).size)
        // The delay after the 429 is doubled for the rest of the session.
        val delays = store.events.filter { it.actionTaken.startsWith("REQUEST_DELAY") }.map { it.actionTaken.substringAfter(' ').toLong() }
        assertTrue(delays.isNotEmpty() && delays.all { it >= 2 * config.baseDelayMs })
    }

    @Test fun forbiddenMarksTheDmInaccessibleAndMovesOn() {
        listed(10, 20, 30)
        transport.enqueue(Responses.noAccess, Responses.unknownChannel, page(emptyList()))
        assertEquals(SessionEnd.Done, run())
        assertEquals(listOf(GetRequest.Messages(10, null), GetRequest.Messages(20, null), GetRequest.Messages(30, null)), transport.reads)
        assertEquals(ChannelState.INACCESSIBLE, store.channels[10]!!.state)
        assertEquals("403 50001", store.channels[10]!!.error)
        assertEquals(ChannelState.GONE, store.channels[20]!!.state)
        assertTrue(store.progress.values.all { it.done })
        assertEquals(1, store.invalidResponseTimesSince(0).size)
    }

    @Test fun anUnknownForbiddenHaltsAndCounts() {
        listed(10)
        transport.enqueue(Responses.json(403, """{"message":"verify","code":40002}"""))
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.UNKNOWN_4XX, end.reason)
        assertEquals(40002, end.code)
        assertEquals(1, store.invalidResponseTimesSince(0).size)
        assertEquals(ChannelState.PENDING, store.channels[10]!!.state)
    }

    @Test fun forbiddenOnTheAccountReadsHalts() {
        transport.enqueue(Responses.noAccess)
        assertEquals(HaltReason.UNKNOWN_4XX, (run() as SessionEnd.Halted).reason)
        assertEquals(listOf<GetRequest>(GetRequest.Me), transport.reads)
    }

    @Test fun serverErrorsBackOffThenPause() {
        listed(10)
        transport.enqueue(Responses.serverError, TransportException("no response"), Responses.emptyServerError, Responses.serverError)
        assertEquals(SessionEnd.PausedTransient, run())
        assertEquals(4, transport.reads.size)
        val backoffs = store.events.filter { it.actionTaken.startsWith("TRANSIENT_BACKOFF") }.map { it.actionTaken.substringAfter(' ').toLong() }
        assertEquals(listOf(30_000L, 120_000L, 600_000L), backoffs)
        assertEquals(Executor.STOP_PAUSED_TRANSIENT, store.fetch!!.stopReason)
        assertTrue(store.actions().contains("NO_RESPONSE"))
    }

    @Test fun aTransientFailureThenSuccessResetsTheCount() {
        listed(10)
        transport.enqueue(Responses.serverError, page(emptyList()))
        assertEquals(SessionEnd.Done, run())
    }

    @Test fun theTripwireFiresAtExactlyFive() {
        listed(10)
        repeat(6) { transport.enqueue(Responses.rateLimited("user", 0.1)) }
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TRIPWIRE, end.reason)
        assertEquals(5, transport.reads.size)
        assertEquals("halted:tripwire", store.fetch!!.stopReason)
    }

    @Test fun theTripwireIsSeededFromTheEventLogOfRunsAndFetches() {
        listed(10)
        repeat(4) { store.event(FetchEventRecord(clock.now - 60_000, 401, 0, null, null, null, "HALT 401")) }
        transport.enqueue(Responses.rateLimited("user", 0.1))
        assertEquals(HaltReason.TRIPWIRE, (run() as SessionEnd.Halted).reason)
        assertEquals(1, transport.reads.size)
    }

    @Test fun rateLimitHeadersAtRemainingZeroAreWaited() {
        listed(10)
        transport.enqueue(
            Responses.json(200, Pages.page(pageRows(10, 5_000, 100)).body, "x-ratelimit-remaining" to "0", "x-ratelimit-reset-after" to "4.5", "x-ratelimit-bucket" to "b9"),
            page(emptyList()),
        )
        assertEquals(SessionEnd.Done, run())
        val reset = store.events.first { it.bucket == "b9" }
        assertEquals(0, reset.remaining)
        assertTrue(store.actions().any { it.startsWith("WAIT_RESET") && it.substringAfter(' ').toLong() >= 4_750 })
    }

    @Test fun theDailyCapCountsReadsAndDeletesOfTheDay() {
        listed(10)
        store.deleteTimes += listOf(clock.now - 1_000, clock.now - 2_000)
        transport.readDefault = { page(pageRows((it as GetRequest.Messages).channelId, (it.before ?: 10_000) - 1, 100)) }
        val end = run(session(c = config.copy(dailyCap = 5)))
        assertEquals(SessionEnd.CapUntil(LocalDateTime.of(2026, 10, 25, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()), end)
        assertEquals(3, transport.reads.size)
        assertEquals(3, store.readsOn(day))
    }

    @Test fun quietHoursStopAFetchBeforeAnyRead() {
        val night = FakeClock(LocalDateTime.of(2026, 10, 24, 2, 0))
        listed(10)
        val s = FetchSession(store, transport, creds, config, night, FakeSleeper(night), Random(1))
        val end = runBlocking { s.runSession() } as SessionEnd.QuietUntil
        assertEquals(LocalDateTime.of(2026, 10, 24, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), end.untilMs)
        assertTrue(transport.reads.isEmpty())
    }

    @Test fun aWaitThatEndsInQuietHoursSendsNothing() {
        val late = FakeClock(LocalDateTime.of(2026, 10, 24, 0, 59, 58))
        listed(10)
        transport.enqueue(page(pageRows(10, 5_000, 100)))
        val s = FetchSession(store, transport, creds, config, late, FakeSleeper(late), Random(1))
        assertTrue(runBlocking { s.runSession() } is SessionEnd.QuietUntil)
        assertEquals(1, transport.reads.size)
    }

    @Test fun aSessionEndsWithABreak() {
        listed(10)
        transport.readDefault = { page(pageRows(10, (( it as GetRequest.Messages).before ?: 1_000_000) - 1, 100)) }
        val end = run()
        assertTrue(end is SessionEnd.Break)
        assertTrue(clock.now - LocalDateTime.of(2026, 10, 24, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli() >= config.sessionMaxMs)
    }

    @Test fun aMessageFoundByThePackageBecomesBothAndKeepsItsRow() {
        listed(10)
        store.messages[102] = FakeFetchStore.Row(ImportedMessage(102, 10, 1, "from the package", false), RowSource.PACKAGE)
        store.channels[10]!!.source = RowSource.PACKAGE
        store.channelsSeen(listOf(ImportedChannel(10, ChannelType.DM, "x", emptyList())), 2)
        transport.enqueue(page(listOf(msg(103, ME, 10), msg(102, ME, 10))))
        run()
        assertEquals(RowSource.BOTH, store.messages[102]!!.source)
        assertEquals("from the package", store.messages[102]!!.message.content)
        assertEquals(RowSource.API, store.messages[103]!!.source)
        assertEquals(RowSource.BOTH, store.channels[10]!!.source)
    }

    @Test fun neverTwoReadsInFlightAndNoPageReadTwiceWithTwoWorkers() {
        val fast = PacingConfig(0, 0, 60 * 60_000L, 0, 0, 0, 0, 0, 0, 10_000, QuietHours(0, 0))
        val reads = Collections.synchronizedList(ArrayList<GetRequest>())
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        val slow = object : Transport {
            override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse = error("no delete in a fetch")
            override fun get(request: GetRequest, headers: HeaderSet): ApiResponse {
                maxSeen.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                try {
                    reads.add(request)
                    Thread.sleep(15)
                    return when (request) {
                        GetRequest.Me -> me()
                        GetRequest.MyChannels -> channels(dm(10), dm(20))
                        is GetRequest.Messages -> {
                            val before = request.before ?: 1_000
                            page(pageRows(request.channelId, before - 1, if (before > 800) 100 else 7))
                        }
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        val lock = Mutex()
        val realClock = app.discoredact.core.executor.SystemClock(ZoneOffset.UTC)
        val ends = runBlocking(Dispatchers.Default) {
            (1..2).map {
                async { FetchSession(store, slow, creds, fast, realClock, Sleeper { }, Random(it), Tripwire(), lock).runSession() }
            }.awaitAll()
        }
        assertTrue(ends.all { it == SessionEnd.Done || it == SessionEnd.Stopped })
        assertEquals(1, maxSeen.get())
        assertEquals(reads.size, reads.toSet().size)
        assertEquals(
            setOf(GetRequest.Me, GetRequest.MyChannels, GetRequest.Messages(10, null), GetRequest.Messages(10, 900), GetRequest.Messages(10, 800),
                GetRequest.Messages(20, null), GetRequest.Messages(20, 900), GetRequest.Messages(20, 800)),
            reads.toSet(),
        )
    }

    @Test fun nothingSecretAppearsInAnyEventEndOrSnapshot() {
        val account = 4_242_424_242_424_242L
        val rows = listOf(msg(105, account, 10, content = "SECRET-OWN-TEXT"), msg(104, OTHER, 10, content = "SECRET-OTHER-TEXT"))
        transport.enqueue(
            me(account), channels(dm(10, 501L to "SECRET-NAME"), dm(20), dm(30)), page(rows),
            Responses.noAccess, Responses.unauthorized,
        )
        val ends = mutableListOf<Any>(run())
        store.fetch!!.stopReason = null
        transport.enqueue(Responses.html)
        ends += run()
        store.fetch!!.stopReason = null
        transport.enqueue(TransportException("no response: SocketTimeoutException"), Responses.captcha)
        ends += run()
        val everything = buildString {
            for (e in store.events) append(e.toString()).append('\n')
            for (e in ends) append(e.toString()).append('\n')
            append(store.fetch()).append('\n')
            append(HistoryRows.own(account, 10, page(rows).body)).append('\n')
            append(page(rows)).append('\n')
            append(TEST_HEADERS).append('\n')
            append(FetchSession.describe(store.fetch!!.stopReason, null, 1))
        }
        for (s in listOf(TEST_HEADERS.authorization, TEST_HEADERS.superProperties, "SECRET-OWN-TEXT", "SECRET-OTHER-TEXT", "SECRET-NAME", account.toString())) {
            assertFalse("leaked $s", everything.contains(s))
        }
        assertTrue(store.messages.values.single().message.content == "SECRET-OWN-TEXT")
    }

    @Test fun haltsReadAsARunsDo() {
        for (r in HaltReason.entries) assertEquals(Executor.describe(r.stopReason, null, 1), FetchSession.describe(r.stopReason, null, 1))
        assertEquals("Finished: every listed DM was read", FetchSession.describe(Executor.STOP_DONE, 5, 1))
        assertTrue(FetchSession.describe(FetchSession.STOP_NO_ACCOUNT_ID, null, 1).startsWith("Paused"))
    }

    @Test fun fiveForbiddenDmsTripTheTripwireAtTheFifth() {
        listed(10, 20, 30, 40, 50, 60)
        repeat(6) { transport.enqueue(Responses.noAccess) }
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TRIPWIRE, end.reason)
        assertEquals(403, end.status)
        assertEquals(5, transport.reads.size)
        assertEquals(listOf(10L, 20L, 30L, 40L, 50L), transport.reads.map { (it as GetRequest.Messages).channelId })
        assertEquals(FetchProgress(60, null, false), store.progress[60])
    }

    @Test fun aSuccessfulReadOpensADmAn403OrA404HadClosed() {
        listed(10, 20, 30)
        store.channels[10]!!.state = ChannelState.INACCESSIBLE; store.channels[10]!!.error = "403 50001"
        store.channels[20]!!.state = ChannelState.GONE; store.channels[20]!!.error = "404 10003"
        store.channels[30]!!.state = ChannelState.DONE
        transport.enqueue(page(emptyList()), page(emptyList()), page(emptyList()))
        assertEquals(SessionEnd.Done, run())
        assertEquals(ChannelState.PENDING, store.channels[10]!!.state)
        assertNull(store.channels[10]!!.error)
        assertEquals(ChannelState.PENDING, store.channels[20]!!.state)
        assertEquals(ChannelState.DONE, store.channels[30]!!.state)
    }

    @Test fun aRequestTheTransportRefusesHaltsNamedAndSendsNothing() {
        listed(10)
        class Refused : java.io.IOException("refused"), app.discoredact.core.executor.RefusedBeforeSending
        transport.enqueue(Refused())
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.REFUSED_LOCALLY, end.reason)
        assertEquals("halted:refused_locally", store.fetch!!.stopReason)
        assertEquals(0, store.readsOn(day))
        assertEquals(FetchProgress(10, null, false), store.progress[10])
        assertTrue(store.actions().contains("HALT refused_locally"))
    }
}
