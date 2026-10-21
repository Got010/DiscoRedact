package app.discoredact.core.model

/** `channels.type` (spec section 5). Only DMs and group DMs exist in this app; guild channels are never imported. */
enum class ChannelType { DM, GROUP_DM }

/** `channels.mode` (spec section 5 and 6a). The default for a newly imported DM is [DELETE_SELECTED] (RJ, OQ332). */
enum class DmMode {
    /** Deletes messages with `selected = true` and `keep = false`. */
    DELETE_SELECTED,

    /** Deletes every message in the DM with `keep = false`. */
    DELETE_ALL_EXCEPT_KEPT;

    companion object {
        val DEFAULT = DELETE_SELECTED
    }
}

/** `channels.state` (spec section 5). */
enum class ChannelState { PENDING, ACTIVE, DONE, INACCESSIBLE, GONE }

/** `messages.status` (spec section 5). */
enum class MessageStatus { PENDING, DELETED, ALREADY_GONE, SKIPPED_SYSTEM, INACCESSIBLE, FAILED }

/** Discord snowflake helpers. IDs are positive 64-bit integers; the top 42 bits are milliseconds since 2015-01-01. */
object Snowflake {
    const val DISCORD_EPOCH_MS = 1_420_070_400_000L

    fun timestampMillis(id: Long): Long = (id ushr 22) + DISCORD_EPOCH_MS

    /** Parses a snowflake from its decimal text; null for anything that is not a positive 64-bit integer. */
    fun parse(text: String?): Long? {
        val t = text?.trim() ?: return null
        if (t.isEmpty() || t.length > 19 || !t.all { it in '0'..'9' }) return null
        val v = t.toLongOrNull() ?: return null
        return if (v > 0) v else null
    }
}
