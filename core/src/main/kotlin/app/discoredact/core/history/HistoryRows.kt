package app.discoredact.core.history

import app.discoredact.core.importer.ImportLimits
import app.discoredact.core.importer.ImportedMessage
import app.discoredact.core.importer.Timestamps
import app.discoredact.core.model.Snowflake
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One page of a DM's history, filtered. Its [toString] carries counts only: no message text, no id. */
sealed interface PageResult {
    class Page(
        /** Only the rows whose `author.id` is the account's own. */
        val own: List<ImportedMessage>,
        /** Rows in the page before any filter (everyone's, malformed ones included): fewer than 100 ends the DM. */
        val pageSize: Int,
        /** The smallest message id in the page, everyone's rows included: the next page's `before`. */
        val oldestId: Long?,
        /** Rows skipped because they could not be read (not an object, no or bad id, no author, an over-long text). */
        val malformed: Int,
        /** Own rows whose `timestamp` could not be parsed; their time comes from the message id. */
        val timestampsFromId: Int,
    ) : PageResult {
        override fun toString(): String =
            "Page(size=$pageSize, own=${own.size}, malformed=$malformed, timestampsFromId=$timestampsFromId)"
    }

    /** The body is not a page: [notJson] when it is not JSON at all (an edge block), else JSON of another shape. */
    data class Bad(val notJson: Boolean) : PageResult
}

/** The JSON reading the three reads share. Never throws; nothing it returns or throws carries body text. */
internal object ReadBodies {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * The parsed body, or null when it is blank or not a JSON object or array. Discord's API answers with one of the two;
     * a bare primitive is not an answer (the parser reads an unquoted word such as `<html>cf</html>` as one).
     */
    fun parse(body: String): JsonElement? {
        if (body.isBlank()) return null
        return try {
            json.parseToJsonElement(body).takeIf { it is JsonObject || it is JsonArray }
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null // absurdly deep nesting: the parser recurses per level
        }
    }

    /** A non-blank body that does not parse: what the response table calls a non-JSON (edge) body. */
    fun notJson(body: String): Boolean = body.isNotBlank() && parse(body) == null

    fun text(el: JsonElement?): String? = (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun snowflake(el: JsonElement?): Long? = Snowflake.parse(text(el))
}

/**
 * The author filter of the Fetch import (CLAUDE.md rule 4 as amended): the only place a fetched row can enter the
 * database, so a message of someone else's can never be written. Pure, tested on the JVM.
 */
object HistoryRows {
    /**
     * Parses one `GET /channels/{channelId}/messages` page (a JSON array of message objects, newest first) and keeps
     * only the rows whose `author.id` equals [accountId]. A row naming another `channel_id` than the one read is
     * skipped as malformed. Everyone else's rows are read here and dropped; nothing of them leaves this function but
     * the page size and the smallest id (for `before`).
     */
    fun own(accountId: Long, channelId: Long, pageJson: String, limits: ImportLimits = ImportLimits()): PageResult {
        require(accountId > 0 && channelId > 0) { "ids must be positive snowflakes" }
        val parsed = ReadBodies.parse(pageJson) ?: return PageResult.Bad(notJson = pageJson.isNotBlank())
        val rows = parsed as? JsonArray ?: return PageResult.Bad(notJson = false)
        val own = ArrayList<ImportedMessage>()
        var oldest: Long? = null
        var malformed = 0
        var fromId = 0
        for (el in rows) {
            val obj = el as? JsonObject
            if (obj == null) {
                malformed++
                continue
            }
            val id = ReadBodies.snowflake(obj["id"])
            if (id == null) {
                malformed++
                continue
            }
            if (oldest == null || id < oldest) oldest = id
            val rowChannel = obj["channel_id"]
            if (rowChannel != null && rowChannel !is JsonNull && ReadBodies.snowflake(rowChannel) != channelId) {
                malformed++
                continue
            }
            val author = ReadBodies.snowflake((obj["author"] as? JsonObject)?.get("id"))
            if (author == null) {
                malformed++
                continue
            }
            if (author != accountId) continue
            val content = ReadBodies.text(obj["content"]).orEmpty()
            if (content.length > limits.maxFieldChars) {
                malformed++
                continue
            }
            val ts = Timestamps.parseMillis(ReadBodies.text(obj["timestamp"]))
                ?: Snowflake.timestampMillis(id).also { fromId++ }
            own.add(ImportedMessage(id, channelId, ts, content, hasAttachments(obj["attachments"])))
        }
        return PageResult.Page(own, rows.size, oldest, malformed, fromId)
    }

    private fun hasAttachments(el: JsonElement?): Boolean = (el as? JsonArray)?.isNotEmpty() == true
}

/** The account's own id from `GET /users/@me`. */
object MeParser {
    /** The `id` of the user object, or null when the body is not JSON, not an object or has no valid id. */
    fun id(json: String): Long? = ReadBodies.snowflake((ReadBodies.parse(json) as? JsonObject)?.get("id"))
}
