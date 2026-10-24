package app.discoredact.core.history

import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.model.ChannelType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The DMs and group DMs of one `GET /users/@me/channels` answer. [toString] carries counts only. */
class ChannelList(
    val channels: List<ImportedChannel>,
    /** Objects of another type (anything but 1 and 3), or with a guild: never imported. */
    val skippedOther: Int,
    /** Rows that could not be read (not an object, no or bad id). */
    val malformed: Int,
) {
    override fun toString(): String = "ChannelList(channels=${channels.size}, skippedOther=$skippedOther, malformed=$malformed)"
}

/** Reads the open DM list (B34 API history, section 3). Guild channels never enter; only types 1 and 3 do. */
object ChannelsParser {
    private const val MAX_NAME_CHARS = 200

    /** The DMs and group DMs, each once, in the order given; null when the body is not a JSON array. */
    fun dms(json: String): ChannelList? {
        val rows = ReadBodies.parse(json) as? JsonArray ?: return null
        val seen = LinkedHashMap<Long, ImportedChannel>()
        var skipped = 0
        var malformed = 0
        for (el in rows) {
            val obj = el as? JsonObject
            val id = obj?.let { ReadBodies.snowflake(it["id"]) }
            if (obj == null || id == null) {
                malformed++
                continue
            }
            val guild = obj["guild_id"]
            val type = when ((obj["type"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull) {
                1 -> ChannelType.DM
                3 -> ChannelType.GROUP_DM
                else -> null
            }
            if (type == null || (guild != null && guild !is JsonNull)) {
                skipped++
                continue
            }
            val recipients = (obj["recipients"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val recipientIds = recipients.mapNotNull { ReadBodies.snowflake(it["id"])?.toString() }
            val names = recipients.mapNotNull { r ->
                (ReadBodies.text(r["global_name"]) ?: ReadBodies.text(r["username"]))?.trim()?.takeIf { it.isNotEmpty() }
            }
            val groupName = if (type == ChannelType.GROUP_DM) ReadBodies.text(obj["name"])?.trim()?.takeIf { it.isNotEmpty() } else null
            val name = (groupName ?: names.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: if (type == ChannelType.DM) "Direct message $id" else "Group DM $id").take(MAX_NAME_CHARS)
            seen.getOrPut(id) { ImportedChannel(id, type, name, recipientIds) }
        }
        return ChannelList(seen.values.toList(), skipped, malformed)
    }
}
