package app.discoredact.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus

/*
 * Tables `channels`, `messages`, `runs` and `events` are spec section 5, column for column (snake_case names as the
 * spec writes them; enums stored as their names). Three side tables hold state the spec asks for but section 5 has
 * no column for, so the section-5 tables stay exactly as specified:
 *   run_messages        the frozen delete set of a run (spec 6a: "computed at final review and frozen into the run")
 *   selection_overrides manual toggles, so they survive filter re-runs (spec 6a precedence: keep > manual > filter)
 *   channel_inclusion   the channel list's select/deselect per channel (spec 6); no row means included
 */

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "type") val type: ChannelType,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Recipient user IDs, comma-separated. */
    @ColumnInfo(name = "recipient_ids") val recipientIds: String,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "mode") val mode: DmMode,
    @ColumnInfo(name = "state") val state: ChannelState,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(entity = ChannelEntity::class, parentColumns = ["channel_id"], childColumns = ["channel_id"]),
    ],
    indices = [
        Index(value = ["channel_id", "status"]),
        Index(value = ["channel_id", "timestamp"]),
    ],
)
data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    /** Epoch milliseconds (UTC). */
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    /** Stored locally for the message browser; null after "Purge local content". */
    @ColumnInfo(name = "content") val content: String?,
    @ColumnInfo(name = "has_attachments") val hasAttachments: Boolean,
    @ColumnInfo(name = "selected") val selected: Boolean,
    @ColumnInfo(name = "keep") val keep: Boolean,
    @ColumnInfo(name = "status") val status: MessageStatus,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_http_status") val lastHttpStatus: Int?,
    @ColumnInfo(name = "last_discord_code") val lastDiscordCode: Int?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "run_id") val runId: Long = 0,
    /** Null until the executor (M2) starts the run; a run frozen at final review has not started. */
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "deleted_count") val deletedCount: Int,
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
)

/** Rolling log, no secrets ever (CLAUDE.md rule 9). `event_id` is a surrogate key: Room needs a primary key. */
@Entity(
    tableName = "events",
    foreignKeys = [ForeignKey(entity = RunEntity::class, parentColumns = ["run_id"], childColumns = ["run_id"])],
    indices = [Index(value = ["run_id"]), Index(value = ["ts"])],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "event_id") val eventId: Long = 0,
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "run_id") val runId: Long?,
    @ColumnInfo(name = "http_status") val httpStatus: Int?,
    @ColumnInfo(name = "discord_code") val discordCode: Int?,
    @ColumnInfo(name = "bucket") val bucket: String?,
    @ColumnInfo(name = "remaining") val remaining: Int?,
    @ColumnInfo(name = "reset_after") val resetAfter: Double?,
    @ColumnInfo(name = "action_taken") val actionTaken: String,
)

/** The frozen delete set of a run, in run order. Written once, in the freeze transaction; never added to after. */
@Entity(
    tableName = "run_messages",
    primaryKeys = ["run_id", "message_id"],
    foreignKeys = [
        ForeignKey(entity = RunEntity::class, parentColumns = ["run_id"], childColumns = ["run_id"]),
        ForeignKey(entity = MessageEntity::class, parentColumns = ["message_id"], childColumns = ["message_id"]),
    ],
    indices = [Index(value = ["message_id"]), Index(value = ["run_id", "position"], unique = true)],
)
data class RunMessageEntity(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "position") val position: Int,
)

/** A manual toggle of `selected` (tap, or multi-select mark/unmark). Filters read it and never override it. */
@Entity(
    tableName = "selection_overrides",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["message_id"], childColumns = ["message_id"])],
)
data class SelectionOverrideEntity(
    @PrimaryKey @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "selected") val selected: Boolean,
)

/** The channel list's select/deselect. No row means the channel is included. */
@Entity(
    tableName = "channel_inclusion",
    foreignKeys = [ForeignKey(entity = ChannelEntity::class, parentColumns = ["channel_id"], childColumns = ["channel_id"])],
)
data class ChannelInclusionEntity(
    @PrimaryKey @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "included") val included: Boolean,
)
