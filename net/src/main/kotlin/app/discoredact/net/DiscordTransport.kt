package app.discoredact.net

import app.discoredact.core.executor.ApiResponse
import app.discoredact.core.executor.DeleteRequest
import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.HeaderSet
import app.discoredact.core.executor.Transport
import app.discoredact.core.executor.TransportException
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The network calls of the app (CLAUDE.md rule 1): `DELETE {base}/channels/{channel_id}/messages/{message_id}`, with
 * the captured headers sent verbatim plus `Origin` and `Referer` (spec section 7), and the Fetch import's three reads
 * ([get]), with the same five headers, `Accept: application/json` and a `Referer` as the client's own reads carry.
 * One client, one request at a time across deletes and reads (the dispatcher allows one, and [oneAtATime] is held
 * around every call), no cache, no cookies, no redirects, no logging interceptor of any kind. Every request is held
 * to [RequestShape] before it reaches the client.
 *
 * [baseUrl] is the production literal; only tests pass another (a mock server) together with an interceptor that
 * allows that host instead. Production code never constructs this with anything but [PRODUCTION_BASE_URL] and the
 * [HostAllowlist].
 */
class DiscordTransport internal constructor(
    private val client: OkHttpClient,
    baseUrl: String,
) : Transport {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val oneAtATime = Any()

    /** Blocks until the previous request has its response: never two in flight, whoever calls (CLAUDE.md rule 3). */
    override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse = synchronized(oneAtATime) { sendLocked(request, headers) }

    /** One read of the Fetch import, under the same lock as [send]: never two in flight, deletes and reads together. */
    override fun get(request: GetRequest, headers: HeaderSet): ApiResponse = synchronized(oneAtATime) { getLocked(request, headers) }

    /** Tests only: sends a request built by hand through the same checks, lock and client. */
    internal fun call(req: Request): ApiResponse = synchronized(oneAtATime) { sendBuilt(req, wholeBody = false) }

    /** The URL of a read, from its shape only: the base, the shape's segments, `limit=100` and `before` when set. */
    internal fun urlOf(request: GetRequest): HttpUrl {
        val b = base.newBuilder()
        for (segment in GetRequest.pathSegments(request)) b.addPathSegment(segment)
        for ((name, value) in GetRequest.query(request)) b.addQueryParameter(name, value)
        return b.build()
    }

    private fun getLocked(request: GetRequest, headers: HeaderSet): ApiResponse {
        val referer = if (request is GetRequest.Messages) "$ORIGIN/channels/@me/${request.channelId}" else "$ORIGIN/channels/@me"
        val req = try {
            Request.Builder()
                .url(urlOf(request))
                .get()
                .header("Authorization", headers.authorization)
                .header("User-Agent", headers.userAgent)
                .header("X-Super-Properties", headers.superProperties)
                .header("X-Discord-Locale", headers.locale)
                .header("X-Discord-Timezone", headers.timezone)
                .header("Accept", "application/json")
                .header("Referer", referer)
                .build()
        } catch (_: IllegalArgumentException) {
            throw TransportException("invalid header value")
        }
        return sendBuilt(req, wholeBody = true)
    }

    private fun sendLocked(request: DeleteRequest, headers: HeaderSet): ApiResponse {
        val url = base.newBuilder()
            .addPathSegment("channels").addPathSegment(request.channelId.toString())
            .addPathSegment("messages").addPathSegment(request.messageId.toString())
            .build()
        // HeaderSet admits printable ASCII only, so OkHttp's own check (whose message echoes a value) cannot fire;
        // if it ever did, the exception is replaced by one that carries nothing (CLAUDE.md rule 9).
        val req = try {
            buildRequest(url, request, headers)
        } catch (_: IllegalArgumentException) {
            throw TransportException("invalid header value")
        }
        return sendBuilt(req, wholeBody = false)
    }

    /** The captured values verbatim, plus Origin and Referer (spec section 7); nothing else is added. */
    private fun buildRequest(url: HttpUrl, request: DeleteRequest, headers: HeaderSet): Request = Request.Builder()
        .url(url)
        .delete()
        .header("Authorization", headers.authorization)
        .header("User-Agent", headers.userAgent)
        .header("X-Super-Properties", headers.superProperties)
        .header("X-Discord-Locale", headers.locale)
        .header("X-Discord-Timezone", headers.timezone)
        .header("Origin", ORIGIN)
        .header("Referer", "$ORIGIN/channels/@me/${request.channelId}")
        .build()

    /**
     * A delete's answer is cut at 64 KiB (enough for any API error; a cut Cloudflare page still classifies as
     * non-JSON). A read's page must be whole to parse ([wholeBody]): one over [MAX_READ_BODY_BYTES] is a failure (no
     * response, transient), never a cut page.
     */
    private fun sendBuilt(req: Request, wholeBody: Boolean): ApiResponse {
        // Checked here as well as in the interceptor, so a client built with another one (tests) is held to it too.
        RequestShape.check(req.method, req.url)
        try {
            client.newCall(req).execute().use { response ->
                val names = response.headers.names()
                val map = HashMap<String, String>(names.size)
                for (n in names) map[n.lowercase()] = response.headers.values(n).joinToString(", ")
                val body = response.body.source().let { src ->
                    if (wholeBody) {
                        if (src.request(MAX_READ_BODY_BYTES + 1)) throw TransportException("response too large")
                        src.buffer.readUtf8()
                    } else {
                        src.request(MAX_BODY_BYTES)
                        src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY_BYTES))
                    }
                }
                return ApiResponse(response.code, map, body)
            }
        } catch (e: HostNotAllowedException) {
            throw e
        } catch (e: RequestNotAllowedException) {
            throw e
        } catch (e: IOException) {
            // The message names the failure kind and the URL at most; never a header.
            throw TransportException("no response: ${e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val PRODUCTION_BASE_URL = "https://discord.com/api/v9"
        const val ORIGIN = "https://discord.com"
        private const val MAX_BODY_BYTES = 64L * 1024

        /** A history page: 100 messages of up to 4,000 characters with their embeds and authors fit many times over. */
        internal const val MAX_READ_BODY_BYTES = 8L * 1024 * 1024

        /** The production transport: the allowlist first, one request at a time, nothing cached or followed. */
        fun production(): DiscordTransport = DiscordTransport(buildClient(HostAllowlist()), PRODUCTION_BASE_URL)

        /** For tests only: a mock server's URL with an interceptor that allows it. */
        internal fun forTests(baseUrl: String, allow: Interceptor): DiscordTransport = DiscordTransport(buildClient(allow), baseUrl)

        internal fun buildClient(allow: Interceptor): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(allow)
            .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
            .cache(null)
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
