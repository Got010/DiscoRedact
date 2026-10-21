package app.discoredact.core.executor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The header set as bytes for the encrypted store: a small JSON object. Only the Keystore-backed store ever sees the
 * plaintext; it never touches a log, a preference or a backup (CLAUDE.md rule 9).
 */
object CredentialCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(h: HeaderSet): ByteArray = JsonObject(
        mapOf(
            "authorization" to JsonPrimitive(h.authorization),
            "user_agent" to JsonPrimitive(h.userAgent),
            "super_properties" to JsonPrimitive(h.superProperties),
            "locale" to JsonPrimitive(h.locale),
            "timezone" to JsonPrimitive(h.timezone),
            "captured_at" to JsonPrimitive(h.capturedAt),
        ),
    ).toString().toByteArray(Charsets.UTF_8)

    /** Null when the bytes are not a complete, valid header set. Never throws with a value in the message. */
    fun decode(bytes: ByteArray): HeaderSet? {
        return try {
            val o = json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return null
            fun s(k: String): String? = o[k]?.jsonPrimitive?.content
            val capturedAt = o["captured_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null
            HeaderSet(
                authorization = s("authorization") ?: return null,
                userAgent = s("user_agent") ?: return null,
                superProperties = s("super_properties") ?: return null,
                locale = s("locale") ?: return null,
                timezone = s("timezone") ?: return null,
                capturedAt = capturedAt,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** What the Credentials screen shows: when the capture was made and whether it has gone stale. Never a value. */
data class CredentialStatus(val capturedAt: Long, val ageMs: Long) {
    val stale: Boolean get() = ageMs >= HeaderSet.STALE_AFTER_MS
    val ageDays: Long get() = ageMs / (24 * 60 * 60 * 1000)
}
