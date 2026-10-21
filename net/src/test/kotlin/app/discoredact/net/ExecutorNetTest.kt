package app.discoredact.net

import app.discoredact.core.executor.DeleteRequest
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.FakeClock
import app.discoredact.core.executor.FakeSleeper
import app.discoredact.core.executor.FakeStore
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.InMemoryCredentialStore
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.TEST_HEADERS
import app.discoredact.core.executor.TransportException
import app.discoredact.core.executor.Tripwire
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.random.Random

/**
 * The executor through the real OkHttp transport against a mock server: every row of spec section 9, one request in
 * flight, the headers verbatim, the halts, the tripwire, and the secret-hygiene check (spec section 11).
 */
class ExecutorNetTest {
    private val server = MockWebServer()
    private val config = PacingConfig.forPreset(PacingPreset.SLOWER)
    private val clock = FakeClock(LocalDateTime.of(2026, 10, 21, 12, 0))
    private val sleeper = FakeSleeper(clock)
    private val store = FakeStore()
    private val creds = InMemoryCredentialStore(TEST_HEADERS)
    private lateinit var transport: DiscordTransport

    /** Tests only: the mock server's host instead of discord.com. Lives in the test source set, nowhere else. */
    private class AllowMock(private val host: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            check(chain.request().url.host == host) { "test interceptor: ${chain.request().url.host}" }
            return chain.proceed(chain.request())
        }
    }

    @Before fun start() {
        server.start()
        transport = DiscordTransport.forTests(server.url("/api/v9").toString(), AllowMock(server.hostName))
    }

    @After fun stop() = server.close()

    private fun executor(tripwire: Tripwire = Tripwire()) = Executor(store, transport, creds, config, clock, sleeper, Random(5), tripwire)
    private fun run() = runBlocking { executor().runSession(1, dryRun = false, oldestFirst = false) }

    private fun res(code: Int, body: String = "", vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code)
        if (body.isNotEmpty()) b.body(body)
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun json(code: Int, discordCode: Int, message: String = "x") = res(code, """{"message": "$message", "code": $discordCode}""", "Content-Type" to "application/json")

    private fun set(vararg ids: Long) {
        store.channel(10, *ids)
        store.freeze(1, *ids)
    }

    private fun requestLine(r: RecordedRequest): String = r.requestLine

    @Test fun aDeleteCarriesExactlyTheCapturedHeadersAndTheOriginAndReferer() {
        set(5)
        server.enqueue(res(204))
        assertEquals(SessionEnd.Done, run())
        val r = server.takeRequest()
        assertEquals("DELETE /api/v9/channels/10/messages/5 HTTP/1.1", requestLine(r))
        assertEquals(TEST_HEADERS.authorization, r.headers["Authorization"])
        assertEquals(TEST_HEADERS.userAgent, r.headers["User-Agent"])
        assertEquals(TEST_HEADERS.superProperties, r.headers["X-Super-Properties"])
        assertEquals(TEST_HEADERS.locale, r.headers["X-Discord-Locale"])
        assertEquals(TEST_HEADERS.timezone, r.headers["X-Discord-Timezone"])
        assertEquals("https://discord.com", r.headers["Origin"])
        assertEquals("https://discord.com/channels/@me/10", r.headers["Referer"])
        assertNull(r.headers["Cookie"])
        assertEquals(MessageStatus.DELETED, store.messages[5]!!.status)
    }

    @Test fun everyRowOfTheResponseTable() {
        set(1, 2, 3, 4, 5, 6)
        server.enqueue(res(204))
        server.enqueue(json(404, 10008, "Unknown Message"))
        server.enqueue(json(400, 50021))
        server.enqueue(res(429, """{"message":"rate limited","retry_after":0.2,"global":false}""", "X-RateLimit-Scope" to "shared", "Retry-After" to "1"))
        server.enqueue(res(204, "", "X-RateLimit-Remaining" to "1", "X-RateLimit-Reset-After" to "0.5", "X-RateLimit-Bucket" to "b1"))
        server.enqueue(res(502, "<html>bad gateway</html>", "Content-Type" to "text/html"))
        val end = run()
        // The HTML 502 is an edge block: a halt, not a transient.
        assertEquals(HaltReason.EDGE_BLOCK, (end as SessionEnd.Halted).reason)
        assertEquals(MessageStatus.DELETED, store.messages[1]!!.status)
        assertEquals(MessageStatus.ALREADY_GONE, store.messages[2]!!.status)
        assertEquals(MessageStatus.SKIPPED_SYSTEM, store.messages[3]!!.status)
        assertEquals(MessageStatus.DELETED, store.messages[4]!!.status) // after the shared-limit wait
        assertEquals(MessageStatus.PENDING, store.messages[5]!!.status)
        assertEquals(6, server.requestCount)
        val reset = store.events.first { it.bucket == "b1" }
        assertEquals(1, reset.remaining); assertEquals(0.5, reset.resetAfter!!, 1e-9)
        assertTrue(store.actions().any { it.startsWith("WAIT_RESET") })
        assertTrue(store.actions().any { it.startsWith("RATE_LIMIT_WAIT") })
    }

    @Test fun unknownChannelAndForbiddenMarkTheChannel() {
        store.channel(10, 1, 2); store.channel(20, 3, 4); store.freeze(1, 1, 2, 3, 4)
        server.enqueue(json(404, 10003, "Unknown Channel"))
        server.enqueue(json(403, 50001, "Missing Access"))
        assertEquals(SessionEnd.Done, run())
        assertEquals(ChannelState.GONE, store.channelStates[10])
        assertEquals(ChannelState.INACCESSIBLE, store.channelStates[20])
        assertEquals(2, server.requestCount)
    }

    @Test fun unauthorizedCaptchaAndUnknownFourHundredsHalt() {
        set(1, 2, 3)
        server.enqueue(json(401, 0, "401: Unauthorized"))
        assertEquals(HaltReason.TOKEN_INVALID, (run() as SessionEnd.Halted).reason)
        store.runs[1]!!.stopReason = null
        server.enqueue(res(400, """{"captcha_key":["captcha-required"],"captcha_sitekey":"k"}"""))
        assertEquals(HaltReason.CAPTCHA, (run() as SessionEnd.Halted).reason)
        store.runs[1]!!.stopReason = null
        server.enqueue(json(403, 40002, "verify"))
        val h = run() as SessionEnd.Halted
        assertEquals(HaltReason.UNKNOWN_4XX, h.reason); assertEquals(403, h.status); assertEquals(40002, h.code)
        assertEquals(MessageStatus.FAILED, store.messages[1]!!.status)
        assertEquals(3, server.requestCount)
    }

    @Test fun theTripwireFiresAtExactlyFiveUserRateLimits() {
        set(1, 2, 3, 4, 5, 6, 7)
        repeat(5) { server.enqueue(res(429, """{"retry_after":0.1,"global":false}""", "X-RateLimit-Scope" to "user")) }
        server.enqueue(res(204))
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TRIPWIRE, end.reason)
        assertEquals(5, server.requestCount)
        assertEquals("halted:tripwire", store.runs[1]!!.stopReason)
    }

    @Test fun aConnectionFailureIsTransient() {
        set(1, 2)
        server.enqueue(res(204))
        val t2 = DiscordTransport.forTests("http://127.0.0.1:1/api/v9", AllowMock("127.0.0.1")) // nothing listens on port 1
        try {
            t2.send(DeleteRequest(1, 1), TEST_HEADERS); throw AssertionError()
        } catch (e: TransportException) {
            assertFalse(e.message!!.contains(TEST_HEADERS.authorization))
        }
    }

    @Test fun neverTwoRequestsInFlightEvenWithTwoCallers() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = inFlight.incrementAndGet()
                maxSeen.updateAndGet { maxOf(it, n) }
                Thread.sleep(150)
                inFlight.decrementAndGet()
                return res(204)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) { i ->
            pool.submit {
                go.await()
                transport.send(DeleteRequest(1, i.toLong()), TEST_HEADERS)
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(2, server.requestCount)
        assertEquals(1, maxSeen.get())
    }

    @Test fun theTokenAppearsNowhereButOnTheWire() {
        set(1, 2, 3, 4, 5, 6)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val captured = StringBuilder()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.append(record.message).append('\n') }
            override fun flush() {}
            override fun close() {}
        }
        val root = Logger.getLogger("")
        root.addHandler(handler)
        val okLogger = Logger.getLogger("okhttp3")
        okLogger.addHandler(handler)
        System.setOut(PrintStream(out)); System.setErr(PrintStream(err))
        val thrown = ArrayList<Throwable>()
        try {
            server.enqueue(json(401, 0))
            server.enqueue(res(400, """{"captcha_key":["x"]}"""))
            server.enqueue(res(503, "<html>cf</html>"))
            server.enqueue(res(429, """{"retry_after":0.1}""", "X-RateLimit-Scope" to "user"))
            server.enqueue(res(500))
            server.enqueue(json(403, 40002))
            repeat(6) {
                try { run() } catch (t: Throwable) { thrown.add(t) }
                store.runs[1]!!.stopReason = null
            }
            val t2 = DiscordTransport.forTests("http://127.0.0.1:1/api/v9", AllowMock("127.0.0.1"))
            try { t2.send(DeleteRequest(1, 1), TEST_HEADERS) } catch (t: Throwable) { thrown.add(t) }
            try { DiscordTransport.forTests("https://cdn.discordapp.com/api/v9", HostAllowlist()).send(DeleteRequest(1, 1), TEST_HEADERS) } catch (t: Throwable) { thrown.add(t) }
        } finally {
            System.setOut(oldOut); System.setErr(oldErr)
            root.removeHandler(handler); okLogger.removeHandler(handler)
        }
        val secrets = listOf(TEST_HEADERS.authorization, TEST_HEADERS.superProperties)
        val everything = buildString {
            append(out.toString()); append(err.toString()); append(captured)
            for (e in store.events) append(listOf(e.actionTaken, e.bucket, e.httpStatus, e.discordCode, e.remaining, e.resetAfter).joinToString(" ")).append('\n')
            for (t in thrown) {
                var c: Throwable? = t
                while (c != null) { append(c.toString()).append('\n'); append(c.stackTraceToString()); c = c.cause }
            }
            for (r in store.runs.values) append(r.stopReason).append('\n')
            append(transport.toString()).append(TEST_HEADERS.toString())
        }
        for (s in secrets) assertFalse("secret leaked", everything.contains(s))
        assertTrue(everything.isNotEmpty())
        // ...and on the wire they were sent verbatim.
        val first = server.takeRequest()
        assertEquals(TEST_HEADERS.authorization, first.headers["Authorization"])
    }
}
