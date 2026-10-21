package app.discoredact.net

import app.discoredact.core.executor.ApiResponse
import app.discoredact.core.executor.DeleteRequest
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
 * The one network call of the app (CLAUDE.md rule 1): `DELETE {base}/channels/{channel_id}/messages/{message_id}`,
 * with the captured headers sent verbatim plus `Origin` and `Referer` (spec section 7). One client, one request at
 * a time (the dispatcher allows one), no cache, no cookies, no redirects, no logging interceptor of any kind.
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

    private fun sendLocked(request: DeleteRequest, headers: HeaderSet): ApiResponse {
        val url = base.newBuilder()
            .addPathSegment("channels").addPathSegment(request.channelId.toString())
            .addPathSegment("messages").addPathSegment(request.messageId.toString())
            .build()
        val req = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", headers.authorization)
            .header("User-Agent", headers.userAgent)
            .header("X-Super-Properties", headers.superProperties)
            .header("X-Discord-Locale", headers.locale)
            .header("X-Discord-Timezone", headers.timezone)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/channels/@me/${request.channelId}")
            .header("Accept", "*/*")
            .build()
        try {
            client.newCall(req).execute().use { response ->
                val names = response.headers.names()
                val map = HashMap<String, String>(names.size)
                for (n in names) map[n.lowercase()] = response.headers.values(n).joinToString(", ")
                val body = response.body.source().let { src ->
                    // Enough for any API error body; a Cloudflare page is cut, which still classifies as non-JSON.
                    src.request(MAX_BODY_BYTES)
                    src.buffer.readUtf8(minOf(src.buffer.size, MAX_BODY_BYTES))
                }
                return ApiResponse(response.code, map, body)
            }
        } catch (e: HostNotAllowedException) {
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
