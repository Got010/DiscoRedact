package app.discoredact.data

import androidx.room.ColumnInfo
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.selection.ChannelSel
import app.discoredact.core.selection.MessageSel

/** A channel row for the channel list, with its live badge counts. */
data class ChannelSummary(
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "type") val type: ChannelType,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "message_count") val messageCount: Int,
    @ColumnInfo(name = "mode") val mode: DmMode,
    @ColumnInfo(name = "state") val state: ChannelState,
    @ColumnInfo(name = "included") val included: Boolean,
    @ColumnInfo(name = "kept") val kept: Int,
    @ColumnInfo(name = "will_delete") val willDelete: Int,
    @ColumnInfo(name = "last_timestamp") val lastTimestamp: Long?,
) {
    fun toSel() = ChannelSel(channelId, displayName, mode, state, included)
}

/** A run's start and end, for the run rules in :core. */
data class RunStateRow(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
) {
    fun toRow() = app.discoredact.core.selection.RunRow(runId, startedAt, endedAt)
}

/** A message row as the browser, the filters and the review read it (with its manual toggle, if any). */
data class MessageRow(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "content") val content: String?,
    @ColumnInfo(name = "has_attachments") val hasAttachments: Boolean,
    @ColumnInfo(name = "selected") val selected: Boolean,
    @ColumnInfo(name = "keep") val keep: Boolean,
    @ColumnInfo(name = "status") val status: MessageStatus,
    @ColumnInfo(name = "manual") val manual: Boolean?,
) {
    fun toSel() = MessageSel(messageId, channelId, timestamp, content, hasAttachments, selected, keep, status, manual)
}
