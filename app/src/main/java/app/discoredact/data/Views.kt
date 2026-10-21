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

/** The next pending message of a run, in run order (the executor's candidate). */
data class CandidateRow(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "position") val position: Int,
)

/** The message row as the delete guard re-reads it. */
data class GuardRowView(
    @ColumnInfo(name = "message_id") val messageId: Long,
    @ColumnInfo(name = "status") val status: MessageStatus,
    @ColumnInfo(name = "keep") val keep: Boolean,
)

data class RunSnapshotRow(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
)

/** Everything the Run screen shows about one run, live. */
data class RunProgress(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    @ColumnInfo(name = "deleted_count") val deletedCount: Int,
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
    @ColumnInfo(name = "dry_run") val dryRun: Boolean?,
    @ColumnInfo(name = "oldest_first") val oldestFirst: Boolean?,
    @ColumnInfo(name = "resume_not_before") val resumeNotBefore: Long?,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "deleted") val deleted: Int,
    @ColumnInfo(name = "already_gone") val alreadyGone: Int,
    @ColumnInfo(name = "skipped_system") val skippedSystem: Int,
    @ColumnInfo(name = "failed") val failed: Int,
    /** Still pending and reachable: what a session would send next. */
    @ColumnInfo(name = "pending") val pending: Int,
    /** Pending but kept, or in a channel that is gone or inaccessible: never sent. */
    @ColumnInfo(name = "blocked") val blocked: Int,
    @ColumnInfo(name = "dry_would_delete") val dryWouldDelete: Int,
    @ColumnInfo(name = "channels") val channels: Int,
) {
    val isDryRun: Boolean get() = dryRun == true
    val settled: Int get() = deleted + alreadyGone + skippedSystem + failed
}

/** A per-channel line of a run's report. */
data class RunChannelLine(
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "state") val state: ChannelState,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "deleted") val deleted: Int,
    @ColumnInfo(name = "pending") val pending: Int,
    @ColumnInfo(name = "dry_would_delete") val dryWouldDelete: Int,
)
