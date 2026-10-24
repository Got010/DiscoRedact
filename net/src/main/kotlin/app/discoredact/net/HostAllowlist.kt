package app.discoredact.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Thrown before any connection is made when a request names a host other than the allowed one (CLAUDE.md rule 2). */
class HostNotAllowedException(host: String, scheme: String) : IOException("request refused: $scheme://$host is not the allowed host")

/** Thrown before any connection is made when a request is not one of the four the app may make (CLAUDE.md rule 1). */
class RequestNotAllowedException(method: String) : IOException("request refused: $method is not one of the four allowed requests")

/**
 * The host allowlist: an application interceptor, installed first, so it runs before DNS, before any connection and
 * before any other interceptor. Only `https://discord.com` passes; `cdn.discordapp.com`, subdomains, look-alikes and
 * plain http are refused. It then holds every request to [RequestShape]: the one delete and the three reads.
 */
class HostAllowlist(private val allowedHost: String = DISCORD_HOST) : Interceptor {
    fun check(url: HttpUrl) {
        if (!url.isHttps || !url.host.equals(allowedHost, ignoreCase = true)) throw HostNotAllowedException(url.host, url.scheme)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        check(request.url)
        RequestShape.check(request.method, request.url)
        return chain.proceed(request)
    }

    companion object {
        const val DISCORD_HOST = "discord.com"
    }
}

/**
 * The four requests of CLAUDE.md rule 1, by method, path and query, under the API base `/api/v9`:
 *
 *  - `DELETE /api/v9/channels/{id}/messages/{id}`, no query;
 *  - `GET /api/v9/users/@me`, no query;
 *  - `GET /api/v9/users/@me/channels`, no query;
 *  - `GET /api/v9/channels/{id}/messages?limit=100` or `?limit=100&before={id}`, exactly so.
 *
 * Anything else (another method, a search, a guild path, another user, another limit, `around`/`after`) is refused
 * before the socket. The transport builds only these (`GetRequest` has three shapes), and checks again here.
 */
object RequestShape {
    private val API_BASE = listOf("api", "v9")
    private const val LIMIT = "100"

    fun allowed(method: String, url: HttpUrl): Boolean {
        if (url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        val segments = url.pathSegments
        if (segments.size < API_BASE.size || segments.subList(0, API_BASE.size) != API_BASE) return false
        val p = segments.drop(API_BASE.size)
        val query = (0 until url.querySize).map { url.queryParameterName(it) to url.queryParameterValue(it) }
        return when (method) {
            "DELETE" -> query.isEmpty() && p.size == 4 && p[0] == "channels" && id(p[1]) && p[2] == "messages" && id(p[3])
            "GET" -> when {
                p == listOf("users", "@me") || p == listOf("users", "@me", "channels") -> query.isEmpty()
                p.size == 3 && p[0] == "channels" && id(p[1]) && p[2] == "messages" ->
                    query == listOf("limit" to LIMIT) ||
                        (query.size == 2 && query[0] == ("limit" to LIMIT) && query[1].first == "before" && id(query[1].second))
                else -> false
            }
            else -> false
        }
    }

    fun check(method: String, url: HttpUrl) {
        if (!allowed(method, url)) throw RequestNotAllowedException(method)
    }

    private fun id(s: String?): Boolean = s != null && s.length in 1..20 && s.all { it in '0'..'9' }
}
