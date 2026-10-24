package app.discoredact.data

import androidx.room.ColumnInfo
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.model.RowSource
import app.discoredact.core.history.FetchProgress
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

/** One line of the Reports totals: how many messages have this status. */
data class StatusCount(
    @ColumnInfo(name = "status") val status: MessageStatus,
    @ColumnInfo(name = "n") val count: Int,
)

/** A message the app could not delete: IDs only, as the leftovers export writes them. */
data class LeftoverRow(
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "message_id") val messageId: Long,
)

/** How often a run saw one HTTP status (and Discord code) other than 204. */
data class RunErrorGroup(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "http_status") val httpStatus: Int,
    @ColumnInfo(name = "discord_code") val discordCode: Int?,
    @ColumnInfo(name = "n") val count: Int,
)

/** Events of a run that carry no response: a request that got none, and retried server or network errors. */
data class RunEventTotals(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "no_response_count") val noResponse: Int,
    @ColumnInfo(name = "transient_count") val transientCount: Int,
)

/** A run's newest halt event, as the event log words it (a reason key and the status, never a header or a text). */
data class RunLastHalt(
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "action_taken") val actionTaken: String,
)

/** Everything the Reports screen shows about one run. */
data class RunReport(
    val progress: RunProgress,
    val errors: List<RunErrorGroup>,
    val noResponse: Int,
    val transientCount: Int,
    val lastHalt: String?,
)

/** One line of the Reports source counts: how many messages each import found (schema 3). */
data class SourceCount(
    @ColumnInfo(name = "source") val source: RowSource,
    @ColumnInfo(name = "n") val count: Int,
)

/** A DM's read progress as the fetch store reads it. */
data class FetchProgressRow(
    @ColumnInfo(name = "channel_id") val channelId: Long,
    @ColumnInfo(name = "oldest_fetched_id") val oldestFetchedId: Long?,
    @ColumnInfo(name = "done") val done: Boolean,
) {
    fun toCore() = FetchProgress(channelId, oldestFetchedId, done)
}

/** The Fetch screen's DM counts: listed, and done. */
data class FetchCounts(
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "done") val done: Int,
)
