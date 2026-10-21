package app.discoredact.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Thrown before any connection is made when a request names a host other than the allowed one (CLAUDE.md rule 2). */
class HostNotAllowedException(host: String, scheme: String) : IOException("request refused: $scheme://$host is not the allowed host")

/**
 * The host allowlist: an application interceptor, installed first, so it runs before DNS, before any connection and
 * before any other interceptor. Only `https://discord.com` passes; `cdn.discordapp.com`, subdomains, look-alikes and
 * plain http are refused.
 */
class HostAllowlist(private val allowedHost: String = DISCORD_HOST) : Interceptor {
    fun check(url: HttpUrl) {
        if (!url.isHttps || !url.host.equals(allowedHost, ignoreCase = true)) throw HostNotAllowedException(url.host, url.scheme)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        check(chain.request().url)
        return chain.proceed(chain.request())
    }

    companion object {
        const val DISCORD_HOST = "discord.com"
    }
}
