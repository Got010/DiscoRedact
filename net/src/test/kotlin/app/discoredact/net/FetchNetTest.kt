package app.discoredact.net

import app.discoredact.core.executor.DeleteRequest
import app.discoredact.core.executor.FakeClock
import app.discoredact.core.executor.FakeSleeper
import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.InMemoryCredentialStore
import app.discoredact.core.executor.SessionEnd
import app.discoredact.core.executor.TEST_HEADERS
import app.discoredact.core.executor.TransportException
import app.discoredact.core.history.FakeFetchStore
import app.discoredact.core.history.FetchProgress
import app.discoredact.core.history.FetchSession
import app.discoredact.core.history.Pages
import app.discoredact.core.history.Pages.ME
import app.discoredact.core.history.Pages.OTHER
import app.discoredact.core.pacing.PacingConfig
import app.discoredact.core.pacing.PacingPreset
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
 * The Fetch import through the real OkHttp transport against a mock server: the three reads exactly as built, the
 * headers verbatim, one request in flight across reads and deletes, no redirect followed, the response table on a
 * read, and the secret-hygiene check.
 */
class FetchNetTest {
    private val server = MockWebServer()
    private val config = PacingConfig.forPreset(PacingPreset.SLOWER)
    private val clock = FakeClock(LocalDateTime.of(2026, 10, 24, 12, 0))
    private val sleeper = FakeSleeper(clock)
    private val store = FakeFetchStore()
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

    private fun run() = runBlocking { FetchSession(store, transport, creds, config, clock, sleeper, Random(3)).runSession() }

    private fun res(code: Int, body: String = "", vararg headers: Pair<String, String>): MockResponse {
        val b = MockResponse.Builder().code(code)
        if (body.isNotEmpty()) b.body(body)
        for ((k, v) in headers) b.addHeader(k, v)
        return b.build()
    }

    private fun json(code: Int, body: String) = res(code, body, "Content-Type" to "application/json")

    private fun listed(vararg dms: Long) {
        store.fetch!!.accountId = ME
        store.fetch!!.capturedAt = TEST_HEADERS.capturedAt
        store.fetch!!.listedAt = 1
        for (d in dms) store.pageDone(d, null, false, 1)
    }

    @Test fun aWholeFetchReadsExactlyTheThreePathsWithTheCapturedHeaders() {
        server.enqueue(json(200, Pages.me().body))
        server.enqueue(json(200, Pages.channels(Pages.dm(10), Pages.dm(20)).body))
        server.enqueue(json(200, Pages.page(Pages.pageRows(10, 5_000, 100)).body))
        server.enqueue(json(200, Pages.page(Pages.pageRows(10, 4_900, 3)).body))
        server.enqueue(json(200, "[]"))
        assertEquals(SessionEnd.Done, run())
        val lines = (1..5).map { server.takeRequest() }
        assertEquals(
            listOf(
                "GET /api/v9/users/@me HTTP/1.1",
                "GET /api/v9/users/@me/channels HTTP/1.1",
                "GET /api/v9/channels/10/messages?limit=100 HTTP/1.1",
                "GET /api/v9/channels/10/messages?limit=100&before=4901 HTTP/1.1",
                "GET /api/v9/channels/20/messages?limit=100 HTTP/1.1",
            ),
            lines.map { it.requestLine },
        )
        for (r in lines) {
            assertEquals(TEST_HEADERS.authorization, r.headers["Authorization"])
            assertEquals(TEST_HEADERS.userAgent, r.headers["User-Agent"])
            assertEquals(TEST_HEADERS.superProperties, r.headers["X-Super-Properties"])
            assertEquals(TEST_HEADERS.locale, r.headers["X-Discord-Locale"])
            assertEquals(TEST_HEADERS.timezone, r.headers["X-Discord-Timezone"])
            assertEquals("application/json", r.headers["Accept"])
            assertNull(r.headers["Cookie"])
            assertNull(r.headers["Origin"])
            assertEquals(0L, r.bodySize)
        }
        assertEquals("https://discord.com/channels/@me", lines[0].headers["Referer"])
        assertEquals("https://discord.com/channels/@me/10", lines[2].headers["Referer"])
        assertEquals(52, store.own(10).size)
        assertEquals(5, server.requestCount)
    }

    @Test fun aRedirectIsNeverFollowed() {
        listed(10)
        server.enqueue(res(302, "", "Location" to "/api/v9/channels/10/messages/search?content=x"))
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.UNKNOWN_4XX, end.reason)
        assertEquals(302, end.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun requestsOutsideTheFourNeverReachTheSocket() {
        for (path in listOf(
            "/api/v9/users/@me/guilds", "/api/v9/channels/1/messages/search?content=x", "/api/v9/guilds/1/messages/search",
            "/api/v9/channels/1/messages?limit=50", "/api/v9/channels/1/messages?limit=100&around=2", "/api/v9/users/2",
        )) {
            try {
                transport.call(Request.Builder().url(server.url(path)).get().build()); fail(path)
            } catch (_: RequestNotAllowedException) {
            }
        }
        try {
            transport.call(Request.Builder().url(server.url("/api/v9/users/@me/channels")).post(okhttp3.RequestBody.EMPTY).build()); fail("post")
        } catch (_: RequestNotAllowedException) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun theResponseTableOnReads() {
        listed(10, 20, 30, 40)
        server.enqueue(json(403, """{"message":"Missing Access","code":50001}"""))
        server.enqueue(json(404, """{"message":"Unknown Channel","code":10003}"""))
        server.enqueue(res(429, """{"message":"rate limited","retry_after":0.5,"global":false}""", "X-RateLimit-Scope" to "shared"))
        server.enqueue(json(500, """{"message":"500: Internal Server Error","code":0}"""))
        server.enqueue(json(200, "[]"))
        server.enqueue(json(401, """{"message":"401: Unauthorized","code":0}"""))
        val end = run() as SessionEnd.Halted
        assertEquals(HaltReason.TOKEN_INVALID, end.reason)
        assertEquals(FetchProgress(10, null, true), store.progress[10])
        assertEquals(FetchProgress(20, null, true), store.progress[20])
        assertEquals(FetchProgress(30, null, true), store.progress[30])
        assertEquals(FetchProgress(40, null, false), store.progress[40])
        assertTrue(store.actions().contains("RATE_LIMITED shared"))
        assertTrue(store.actions().any { it.startsWith("TRANSIENT_BACKOFF 30000") })
        assertEquals(6, server.requestCount)
        // Two invalid responses counted: the 403 and the 401; the shared 429 is not.
        assertEquals(2, store.invalidResponseTimesSince(0).size)
    }

    @Test fun captchaAndHtmlHalt() {
        listed(10)
        server.enqueue(json(400, """{"captcha_key":["captcha-required"],"captcha_sitekey":"k"}"""))
        assertEquals(HaltReason.CAPTCHA, (run() as SessionEnd.Halted).reason)
        store.fetch!!.stopReason = null
        server.enqueue(res(200, "<html>cf</html>", "Content-Type" to "text/html"))
        assertEquals(HaltReason.EDGE_BLOCK, (run() as SessionEnd.Halted).reason)
        assertTrue(store.messages.isEmpty())
    }

    @Test fun theTripwireFiresAtFiveOnReads() {
        listed(10)
        repeat(6) { server.enqueue(res(429, """{"retry_after":0.1,"global":false}""", "X-RateLimit-Scope" to "user")) }
        assertEquals(HaltReason.TRIPWIRE, (run() as SessionEnd.Halted).reason)
        assertEquals(5, server.requestCount)
    }

    @Test fun aPageLargerThanTheCapIsNoResponseNeverACutPage() {
        listed(10)
        val big = "[" + "x".repeat((DiscordTransport.MAX_READ_BODY_BYTES + 10).toInt()) + "]"
        server.enqueue(json(200, big))
        try {
            transport.get(GetRequest.Messages(10, null), TEST_HEADERS); fail()
        } catch (e: TransportException) {
            assertEquals("response too large", e.message)
        }
        // The cap is 2 MiB, and Discord's largest page (100 messages of 4,000 three-byte characters) is under it.
        assertEquals(2L * 1024 * 1024, DiscordTransport.MAX_READ_BODY_BYTES)
        val worst = Pages.page((0 until 100).map { Pages.msg(5_000L - it, ME, 10, content = "\u20ac".repeat(4_000)) }).body
        assertTrue(worst.toByteArray().size in 1_200_000 until DiscordTransport.MAX_READ_BODY_BYTES.toInt())
        server.enqueue(json(200, worst))
        assertEquals(worst, transport.get(GetRequest.Messages(10, null), TEST_HEADERS).body)
        // A page just under the cap is read whole.
        val rows = Pages.page(Pages.pageRows(10, 5_000, 100)).body
        server.enqueue(json(200, rows))
        assertEquals(rows, transport.get(GetRequest.Messages(10, null), TEST_HEADERS).body)
    }

    @Test fun neverTwoInFlightAcrossReadsAndDeletes() {
        val inFlight = AtomicInteger()
        val maxSeen = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                maxSeen.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
                Thread.sleep(100)
                inFlight.decrementAndGet()
                return if (request.method == "GET") json(200, "[]") else res(204)
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        val done = CountDownLatch(4)
        repeat(4) { i ->
            pool.submit {
                go.await()
                if (i % 2 == 0) transport.get(GetRequest.Messages(1, null), TEST_HEADERS) else transport.send(DeleteRequest(1, i.toLong()), TEST_HEADERS)
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(4, server.requestCount)
        assertEquals(1, maxSeen.get())
    }

    @Test fun theTokenAndThePagesAppearNowhereButOnTheWire() {
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
        val ends = ArrayList<Any>()
        val thrown = ArrayList<Throwable>()
        try {
            server.enqueue(json(200, Pages.me().body))
            server.enqueue(json(200, Pages.channels(Pages.dm(10, 501L to "SECRET-NAME"), Pages.dm(20)).body))
            server.enqueue(json(200, Pages.page(listOf(Pages.msg(105, ME, 10, content = "SECRET-OWN"), Pages.msg(104, OTHER, 10, content = "SECRET-OTHER"))).body))
            server.enqueue(json(401, """{"message":"401: Unauthorized","code":0}"""))
            ends += run()
            store.fetch!!.stopReason = null
            server.enqueue(res(503, "<html>SECRET-EDGE</html>"))
            ends += run()
            try { DiscordTransport.forTests("https://cdn.discordapp.com/api/v9", HostAllowlist()).get(GetRequest.Me, TEST_HEADERS) } catch (t: Throwable) { thrown.add(t) }
            try { transport.call(Request.Builder().url(server.url("/api/v9/users/@me/guilds")).header("Authorization", TEST_HEADERS.authorization).build()) } catch (t: Throwable) { thrown.add(t) }
        } finally {
            System.setOut(oldOut); System.setErr(oldErr)
            root.removeHandler(handler); okLogger.removeHandler(handler)
        }
        val everything = buildString {
            append(out.toString()); append(err.toString()); append(captured)
            for (e in store.events) append(e.toString()).append('\n')
            for (e in ends) append(e.toString()).append('\n')
            for (t in thrown) {
                var c: Throwable? = t
                while (c != null) { append(c.toString()).append('\n'); append(c.stackTraceToString()); c = c.cause }
            }
            append(store.fetch()).append(transport.toString())
        }
        for (s in listOf(TEST_HEADERS.authorization, TEST_HEADERS.superProperties, "SECRET-NAME", "SECRET-OWN", "SECRET-OTHER", "SECRET-EDGE")) {
            assertFalse("leaked $s", everything.contains(s))
        }
        assertEquals(2, thrown.size)
        assertEquals(TEST_HEADERS.authorization, server.takeRequest().headers["Authorization"])
    }

    /** L-6: the transport's own refusal halts the session by name; the worker does not die, nothing reaches the server. */
    @Test fun aRequestTheTransportRefusesHaltsTheFetchByName() {
        listed(10)
        val wrongBase = DiscordTransport.forTests(server.url("/api/v10").toString(), AllowMock(server.hostName))
        val end = runBlocking { FetchSession(store, wrongBase, creds, config, clock, sleeper, Random(3)).runSession() } as SessionEnd.Halted
        assertEquals(HaltReason.REFUSED_LOCALLY, end.reason)
        assertEquals(0, server.requestCount)
        val wrongHost = DiscordTransport.forTests("https://cdn.discordapp.com/api/v9", HostAllowlist())
        store.fetch!!.stopReason = null
        val end2 = runBlocking { FetchSession(store, wrongHost, creds, config, clock, sleeper, Random(3)).runSession() } as SessionEnd.Halted
        assertEquals(HaltReason.REFUSED_LOCALLY, end2.reason)
    }
}
