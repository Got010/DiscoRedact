package app.discoredact.core.executor

/**
 * The captured browser session's headers, sent verbatim on every request (spec section 7). Never logged, never
 * put in an exception message, never in an event row (CLAUDE.md rule 9): [toString] deliberately hides the values.
 */
class HeaderSet(
    val authorization: String,
    val userAgent: String,
    val superProperties: String,
    val locale: String,
    val timezone: String,
    /** When the capture was made (epoch millis); the app warns after about 14 days. */
    val capturedAt: Long,
) {
    init {
        require(authorization.isNotBlank() && userAgent.isNotBlank() && superProperties.isNotBlank()) { "incomplete header set" }
        require(listOf(authorization, userAgent, superProperties, locale, timezone).none { it.any { c -> c == '\r' || c == '\n' } }) {
            "header values must not contain line breaks"
        }
    }

    val ageMs: (Long) -> Long = { now -> now - capturedAt }

    override fun toString(): String = "HeaderSet(captured_at=$capturedAt, values hidden)"

    companion object {
        /** Headers older than this are stale: the client build in X-Super-Properties no longer matches a live client. */
        const val STALE_AFTER_MS: Long = 14L * 24 * 60 * 60 * 1000
    }
}

/** Where the executor gets the header set. M2 ships an empty store and an in-memory one for tests; M3 adds the Keystore one. */
interface CredentialStore {
    /** The complete header set, or null when none is stored (a real run then refuses to start). */
    fun load(): HeaderSet?
}

object EmptyCredentialStore : CredentialStore {
    override fun load(): HeaderSet? = null
}

class InMemoryCredentialStore(@Volatile var headers: HeaderSet?) : CredentialStore {
    override fun load(): HeaderSet? = headers
}
