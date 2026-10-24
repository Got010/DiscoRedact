package app.discoredact.core.executor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

enum class RateLimitScope { USER, GLOBAL, SHARED }

/** Discord's rate-limit headers (spec section 8; https://discord.com/developers/docs/topics/rate-limits). */
data class RateLimitInfo(
    val bucket: String?,
    val remaining: Int?,
    /** Seconds until the bucket resets, as the header says. */
    val resetAfterSeconds: Double?,
    val scope: RateLimitScope?,
) {
    companion object {
        fun from(r: ApiResponse): RateLimitInfo = RateLimitInfo(
            bucket = r.header("x-ratelimit-bucket")?.takeIf { it.isNotBlank() }?.take(64),
            remaining = r.header("x-ratelimit-remaining")?.trim()?.toIntOrNull(),
            resetAfterSeconds = r.header("x-ratelimit-reset-after")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            scope = when (r.header("x-ratelimit-scope")?.trim()?.lowercase()) {
                "user" -> RateLimitScope.USER
                "global" -> RateLimitScope.GLOBAL
                "shared" -> RateLimitScope.SHARED
                else -> null
            },
        )
    }
}

enum class HaltReason(val key: String) {
    /** 401: the token is invalid or expired; re-capture the headers. */
    TOKEN_INVALID("401"),

    /** The body carries `captcha_key`: a challenge the app never solves. */
    CAPTCHA("captcha"),

    /** A non-JSON (HTML) body: an edge block. Manual resume only, not before an hour. */
    EDGE_BLOCK("edge"),

    /** Five invalid responses (401, 403, 429 not shared) within ten minutes. */
    TRIPWIRE("tripwire"),

    /** A 4xx the table does not name: surfaced with its status and code. */
    UNKNOWN_4XX("unknown_4xx"),

    /** A read (the Fetch import) answered 2xx with JSON of a shape the app does not expect. Nothing of it is written. */
    UNEXPECTED_BODY("unexpected_body"),

    /** The transport refused the request itself (another host, or not one of the four): nothing was sent. */
    REFUSED_LOCALLY("refused_locally");

    val stopReason: String get() = "halted:$key"
}

/** What one response means for the message, the channel or the run (spec section 9). */
sealed interface Outcome {
    data object Deleted : Outcome
    data object AlreadyGone : Outcome
    data object ChannelGone : Outcome
    data class ChannelInaccessible(val code: Int) : Outcome
    data object SystemMessage : Outcome
    data class RateLimited(val scope: RateLimitScope, val retryAfterMs: Long) : Outcome {
        /** Shared-resource limits are Discord's, not ours: not counted by the tripwire, no delay doubling. */
        val countsAsInvalid: Boolean get() = scope != RateLimitScope.SHARED
    }
    data class Halt(val reason: HaltReason, val status: Int?, val code: Int?) : Outcome
    /** 5xx, or no response at all. */
    data class Transient(val status: Int?) : Outcome
}

/** The response table of spec section 9 as one pure function. No limit is hard-coded (CLAUDE.md rule 7). */
object ResponseClassifier {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** The Discord error code in a JSON body, if any. */
    fun discordCode(r: ApiResponse): Int? = parseObject(r.body)?.get("code")?.let { (it as? JsonPrimitive)?.intOrNull }

    fun classify(r: ApiResponse): Outcome {
        if (r.status in 200..299) return Outcome.Deleted
        if (r.body.contains("captcha_key")) return Outcome.Halt(HaltReason.CAPTCHA, r.status, null)
        val body = parseObject(r.body)
        if (body == null && r.body.isNotBlank()) {
            // Not JSON: Cloudflare's HTML or anything else that is not Discord's API talking. Halt; manual resume.
            return Outcome.Halt(HaltReason.EDGE_BLOCK, r.status, null)
        }
        val code = body?.get("code")?.let { (it as? JsonPrimitive)?.intOrNull }
        return when (r.status) {
            429 -> {
                val info = RateLimitInfo.from(r)
                val global = body?.get("global")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true
                val scope = when {
                    global -> RateLimitScope.GLOBAL
                    info.scope != null -> info.scope
                    else -> RateLimitScope.USER
                }
                val retryAfterSeconds = body?.get("retry_after")?.let { (it as? JsonPrimitive)?.doubleOrNull }
                    ?: r.header("retry-after")?.trim()?.toDoubleOrNull()
                    ?: info.resetAfterSeconds
                    ?: DEFAULT_RETRY_AFTER_SECONDS
                Outcome.RateLimited(scope, secondsToMillis(retryAfterSeconds))
            }
            404 -> when (code) {
                10008 -> Outcome.AlreadyGone
                10003 -> Outcome.ChannelGone
                else -> Outcome.Halt(HaltReason.UNKNOWN_4XX, 404, code)
            }
            403 -> when (code) {
                50001, 50013 -> Outcome.ChannelInaccessible(code)
                else -> Outcome.Halt(HaltReason.UNKNOWN_4XX, 403, code)
            }
            400 -> if (code == 50021) Outcome.SystemMessage else Outcome.Halt(HaltReason.UNKNOWN_4XX, 400, code)
            401 -> Outcome.Halt(HaltReason.TOKEN_INVALID, 401, code)
            in 500..599 -> Outcome.Transient(r.status)
            in 400..499 -> Outcome.Halt(HaltReason.UNKNOWN_4XX, r.status, code)
            else -> Outcome.Halt(HaltReason.UNKNOWN_4XX, r.status, code)
        }
    }

    private fun parseObject(body: String): JsonObject? {
        if (body.isBlank()) return null
        return try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }
    }

    private fun secondsToMillis(seconds: Double): Long =
        if (!seconds.isFinite() || seconds < 0) secondsToMillis(DEFAULT_RETRY_AFTER_SECONDS) else Math.ceil(seconds * 1000.0).toLong()

    /** Only when a 429 names no wait at all, which Discord's API never does. */
    const val DEFAULT_RETRY_AFTER_SECONDS = 5.0
}
