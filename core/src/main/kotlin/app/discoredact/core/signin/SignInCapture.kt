package app.discoredact.core.signin

import app.discoredact.core.executor.HeaderSet

/**
 * The sign-in screen's capture: the same five headers the cURL import takes, read from one of the page's own requests
 * to Discord's API. Header names match in any case; nothing but the five is read. A result or a failure names fields
 * only, never a value (CLAUDE.md rule 9), and nothing here throws.
 */
object SignInCapture {
    const val API_HOST = "discord.com"
    private const val API_PATH = "/api/"

    /** The five fields, by lower-case header name, in the order the screens name them. */
    val FIELDS: List<String> = listOf("authorization", "user-agent", "x-super-properties", "x-discord-locale", "x-discord-timezone")

    /** What one request showed. No case carries a header value in its fields or its text. */
    sealed interface Outcome {
        /** Not a request to Discord's API (or not one this capture reads): nothing was read. */
        data object NotApi : Outcome

        /** A request to Discord's API that showed none of Discord's own headers: the page's headers may not be exposed. */
        data object HeadersHidden : Outcome

        /** Discord's headers but no Authorization: the page is not signed in yet. */
        data object NotSignedIn : Outcome

        /** Signed in, but a field is missing or not usable (outside printable ASCII). Names only. */
        data class Unusable(val missing: List<String>, val invalid: List<String>) : Outcome

        /** All five present and valid. */
        class Captured(val headers: HeaderSet) : Outcome {
            override fun toString(): String = "Captured(values hidden)"
        }
    }

    /** True for an https request to Discord's API on discord.com itself (no subdomain, no port, no user info). */
    fun isApi(url: String): Boolean {
        val a = SignInHosts.parse(url) ?: return false
        return a.hasAuthority && a.scheme.equals("https", ignoreCase = true) && !a.userInfo && a.port == null &&
            a.host == API_HOST && a.path.startsWith(API_PATH)
    }

    /**
     * The header set from a request whose [url] is under Discord's API, or null. [fallbackUserAgent] is the page's own
     * user agent, used when the request does not list a User-Agent header.
     */
    fun fromRequest(url: String, headers: Map<String, String>, fallbackUserAgent: String, now: Long): HeaderSet? =
        (inspect(url, headers, fallbackUserAgent, now) as? Outcome.Captured)?.headers

    fun inspect(url: String, headers: Map<String, String>, fallbackUserAgent: String, now: Long): Outcome = try {
        if (!isApi(url)) {
            Outcome.NotApi
        } else {
            val found = HashMap<String, String>(8)
            for (entry in headers.entries) {
                // The platform's map may hold a null name or value despite its type.
                val name: String? = entry.key
                val value: String? = entry.value
                val key = name?.trim()?.lowercase() ?: continue
                if (key in FIELDS && value != null) found[key] = value.trim()
            }
            val discordOwn = FIELDS.drop(2).any { !found[it].isNullOrEmpty() }
            when {
                found["authorization"].isNullOrEmpty() -> if (discordOwn) Outcome.NotSignedIn else Outcome.HeadersHidden
                else -> {
                    if (found["user-agent"].isNullOrEmpty()) found["user-agent"] = fallbackUserAgent.trim()
                    val missing = FIELDS.filter { found[it].isNullOrBlank() }
                    val invalid = FIELDS.filter { it !in missing && !found.getValue(it).all { c -> c in ' '..'~' } }
                    if (missing.isNotEmpty() || invalid.isNotEmpty()) {
                        Outcome.Unusable(missing, invalid)
                    } else {
                        Outcome.Captured(
                            HeaderSet(
                                authorization = found.getValue("authorization"),
                                userAgent = found.getValue("user-agent"),
                                superProperties = found.getValue("x-super-properties"),
                                locale = found.getValue("x-discord-locale"),
                                timezone = found.getValue("x-discord-timezone"),
                                capturedAt = now,
                            ),
                        )
                    }
                }
            }
        }
    } catch (_: Exception) {
        // HeaderSet names fields only, but nothing from here may carry a message at all.
        Outcome.Unusable(emptyList(), FIELDS)
    }
}
