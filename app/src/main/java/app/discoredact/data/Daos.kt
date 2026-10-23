package app.discoredact.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import kotlinx.coroutines.flow.Flow

private const val SUMMARY_SELECT = """
    SELECT c.channel_id, c.type, c.display_name, c.message_count, c.mode, c.state,
        COALESCE(i.included, 1) AS included,
        (SELECT COUNT(*) FROM messages m WHERE m.channel_id = c.channel_id AND m.keep = 1) AS kept,
        CASE WHEN COALESCE(i.included, 1) = 1 AND c.state NOT IN ('GONE', 'INACCESSIBLE') THEN
            (SELECT COUNT(*) FROM messages m WHERE m.channel_id = c.channel_id AND m.keep = 0
                AND m.status = 'PENDING' AND (c.mode = 'DELETE_ALL_EXCEPT_KEPT' OR m.selected = 1))
        ELSE 0 END AS will_delete,
        (SELECT MAX(m.timestamp) FROM messages m WHERE m.channel_id = c.channel_id) AS last_timestamp
    FROM channels c LEFT JOIN channel_inclusion i ON i.channel_id = c.channel_id
"""

private const val ROW_SELECT = """
    SELECT m.message_id, m.channel_id, m.timestamp, m.content, m.has_attachments, m.selected, m.keep, m.status,
        o.selected AS manual
    FROM messages m LEFT JOIN selection_overrides o ON o.message_id = m.message_id
"""

/**
 * The leftovers (spec section 10): messages the app could not delete. Not kept and still pending in a channel the alt
 * can no longer reach, or refused by Discord (the unknown-4xx outcome). Expects `messages m` joined to `channels c`.
 */
private const val LEFTOVER_WHERE = """
    (m.keep = 0 AND m.status = 'PENDING' AND c.state IN ('INACCESSIBLE', 'GONE')) OR m.status = 'FAILED'
"""

/** Every run with the counts of its frozen set; [PROGRESS_SELECT] is the one definition behind the Run screen and the Reports. */
private const val PROGRESS_SELECT =
    "SELECT r.run_id, r.started_at, r.ended_at, r.deleted_count, r.stop_reason, o.dry_run, o.oldest_first, o.resume_not_before, " +
        "(SELECT COUNT(*) FROM run_messages rm WHERE rm.run_id = r.run_id) AS total, " +
        "(SELECT COUNT(*) FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id WHERE rm.run_id = r.run_id AND m.status = 'DELETED') AS deleted, " +
        "(SELECT COUNT(*) FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id WHERE rm.run_id = r.run_id AND m.status = 'ALREADY_GONE') AS already_gone, " +
        "(SELECT COUNT(*) FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id WHERE rm.run_id = r.run_id AND m.status = 'SKIPPED_SYSTEM') AS skipped_system, " +
        "(SELECT COUNT(*) FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id WHERE rm.run_id = r.run_id AND m.status = 'FAILED') AS failed, " +
        "(SELECT COUNT(*) FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id JOIN channels c ON c.channel_id = rm.channel_id " +
        "  WHERE rm.run_id = r.run_id AND m.status = 'PENDING' AND m.keep = 0 AND c.state NOT IN ('GONE', 'INACCESSIBLE')) AS pending, " +
        "(SELECT COUNT(*) FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id JOIN channels c ON c.channel_id = rm.channel_id " +
        "  WHERE rm.run_id = r.run_id AND m.status = 'PENDING' AND (m.keep = 1 OR c.state IN ('GONE', 'INACCESSIBLE'))) AS blocked, " +
        "(SELECT COUNT(*) FROM run_results rr WHERE rr.run_id = r.run_id AND rr.verdict = 'WOULD_DELETE') AS dry_would_delete, " +
        "(SELECT COUNT(DISTINCT rm.channel_id) FROM run_messages rm WHERE rm.run_id = r.run_id) AS channels " +
        "FROM runs r LEFT JOIN run_options o ON o.run_id = r.run_id "

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(channels: List<ChannelEntity>): List<Long>

    /** Re-import keeps mode, state and selections; only the names from the package are refreshed. */
    @Query("UPDATE channels SET display_name = :name, recipient_ids = :recipients WHERE channel_id = :id")
    fun updateNames(id: Long, name: String, recipients: String)

    @Query("UPDATE channels SET message_count = (SELECT COUNT(*) FROM messages m WHERE m.channel_id = channels.channel_id)")
    fun recountMessages()

    @Query("$SUMMARY_SELECT ORDER BY last_timestamp IS NULL, last_timestamp DESC, c.channel_id")
    fun observeSummaries(): Flow<List<ChannelSummary>>

    @Query("$SUMMARY_SELECT WHERE c.channel_id = :id")
    fun observeSummary(id: Long): Flow<ChannelSummary?>

    @Query("$SUMMARY_SELECT ORDER BY last_timestamp IS NULL, last_timestamp DESC, c.channel_id")
    suspend fun summaries(): List<ChannelSummary>

    @Query("UPDATE channels SET mode = :mode WHERE channel_id = :id")
    suspend fun setMode(id: Long, mode: DmMode)

    @Query("INSERT OR REPLACE INTO channel_inclusion (channel_id, included) VALUES (:id, :included)")
    suspend fun setIncluded(id: Long, included: Boolean)

    @Query("INSERT OR REPLACE INTO channel_inclusion (channel_id, included) SELECT channel_id, :included FROM channels")
    suspend fun setAllIncluded(included: Boolean)

    @Query("SELECT COUNT(*) FROM channels")
    fun observeCount(): Flow<Int>
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIgnore(messages: List<MessageEntity>): List<Long>

    @Query(
        "$ROW_SELECT WHERE m.channel_id = :channelId AND (:query IS NULL OR m.content LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY m.timestamp DESC, m.message_id DESC LIMIT :limit OFFSET :offset",
    )
    suspend fun page(channelId: Long, query: String?, limit: Int, offset: Int): List<MessageRow>

    @Query("SELECT COUNT(*) FROM messages m WHERE m.channel_id = :channelId AND (:query IS NULL OR m.content LIKE '%' || :query || '%' ESCAPE '\\')")
    fun observeCount(channelId: Long, query: String?): Flow<Int>

    /** Keyset page, newest first, for streaming a channel through the selection rules. */
    @Query(
        "$ROW_SELECT WHERE m.channel_id = :channelId AND (m.timestamp < :beforeTs OR (m.timestamp = :beforeTs AND m.message_id < :beforeId)) " +
            "ORDER BY m.timestamp DESC, m.message_id DESC LIMIT :limit",
    )
    suspend fun keysetPage(channelId: Long, beforeTs: Long, beforeId: Long, limit: Int): List<MessageRow>

    /** As [keysetPage] without the text: what the delete plan needs. */
    @Query(
        "SELECT m.message_id, m.channel_id, m.timestamp, NULL AS content, m.has_attachments, m.selected, m.keep, m.status, " +
            "o.selected AS manual FROM messages m LEFT JOIN selection_overrides o ON o.message_id = m.message_id " +
            "WHERE m.channel_id = :channelId AND (m.timestamp < :beforeTs OR (m.timestamp = :beforeTs AND m.message_id < :beforeId)) " +
            "ORDER BY m.timestamp DESC, m.message_id DESC LIMIT :limit",
    )
    suspend fun keysetPageNoContent(channelId: Long, beforeTs: Long, beforeId: Long, limit: Int): List<MessageRow>

    @Query("$ROW_SELECT WHERE m.message_id IN (:ids)")
    suspend fun rowsByIds(ids: List<Long>): List<MessageRow>

    @Query("UPDATE messages SET keep = :keep, updated_at = :now WHERE message_id IN (:ids)")
    suspend fun setKeep(ids: List<Long>, keep: Boolean, now: Long)

    @Query("UPDATE messages SET selected = :selected, updated_at = :now WHERE message_id IN (:ids)")
    suspend fun setSelected(ids: List<Long>, selected: Boolean, now: Long)

    /**
     * A filter's write. Messages with a manual toggle are excluded in SQL as well, so a toggle made while the filter
     * was reading pages is never overwritten (precedence: manual > filter).
     */
    @Query(
        "UPDATE messages SET selected = :selected, updated_at = :now WHERE message_id IN (:ids) " +
            "AND message_id NOT IN (SELECT message_id FROM selection_overrides)",
    )
    suspend fun setSelectedByFilter(ids: List<Long>, selected: Boolean, now: Long)

    @Query("INSERT OR REPLACE INTO selection_overrides (message_id, selected) SELECT message_id, :selected FROM messages WHERE message_id IN (:ids)")
    suspend fun setOverrides(ids: List<Long>, selected: Boolean)

    @Query("UPDATE messages SET content = NULL")
    suspend fun purgeContent()

    @Query("SELECT COUNT(*) FROM messages WHERE content IS NOT NULL")
    fun observeContentCount(): Flow<Int>

    // ---- Reports and the leftovers export -------------------------------------------------------------------------

    @Query("SELECT status, COUNT(*) AS n FROM messages GROUP BY status")
    fun observeStatusCounts(): Flow<List<StatusCount>>

    @Query("SELECT COUNT(*) FROM messages WHERE keep = 1")
    fun observeKeptCount(): Flow<Int>

    @Query("SELECT m.channel_id, m.message_id FROM messages m JOIN channels c ON c.channel_id = m.channel_id WHERE $LEFTOVER_WHERE ORDER BY m.channel_id, m.message_id")
    suspend fun leftovers(): List<LeftoverRow>

    @Query("SELECT COUNT(*) FROM messages m JOIN channels c ON c.channel_id = m.channel_id WHERE $LEFTOVER_WHERE")
    fun observeLeftoverCount(): Flow<Int>
}

@Dao
interface RunDao {
    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRunMessages(rows: List<RunMessageEntity>)

    @Query("SELECT COUNT(*) FROM run_messages WHERE run_id = :runId")
    suspend fun runSize(runId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM run_messages WHERE run_id = :runId AND message_id = :messageId)")
    suspend fun inRun(runId: Long, messageId: Long): Boolean

    @Query("SELECT run_id, started_at, ended_at FROM runs")
    suspend fun runStates(): List<RunStateRow>

    @Query("UPDATE runs SET ended_at = :now, stop_reason = :reason WHERE run_id IN (:ids)")
    suspend fun close(ids: List<Long>, now: Long, reason: String)

    @Query("SELECT COUNT(*) FROM runs")
    fun observeRunCount(): Flow<Int>

    // ---- Executor (M2). Blocking methods: the worker calls them off the main thread, and each write is committed
    // before the executor moves to the next message (CLAUDE.md rule 6). -----------------------------------------

    @Insert
    fun insertOptions(options: RunOptionsEntity)

    @Query("SELECT * FROM run_options WHERE run_id = :runId")
    fun options(runId: Long): RunOptionsEntity?

    @Query(
        "SELECT rm.message_id, rm.channel_id, rm.position FROM run_messages rm " +
            "JOIN messages m ON m.message_id = rm.message_id JOIN channels c ON c.channel_id = rm.channel_id " +
            "WHERE rm.run_id = :runId AND m.status = 'PENDING' AND m.keep = 0 AND c.state NOT IN ('GONE', 'INACCESSIBLE') " +
            "AND (:dryRun = 0 OR rm.message_id NOT IN (SELECT rr.message_id FROM run_results rr WHERE rr.run_id = :runId)) " +
            "ORDER BY rm.position ASC LIMIT 1",
    )
    fun nextCandidateNewestFirst(runId: Long, dryRun: Boolean): CandidateRow?

    /**
     * Channels in run order, the oldest message first within each (positions run newest first within a channel). The
     * channel rank is computed once per query in a derived table, not per row.
     */
    @Query(
        "SELECT rm.message_id, rm.channel_id, rm.position FROM run_messages rm " +
            "JOIN (SELECT channel_id, MIN(position) AS first_position FROM run_messages WHERE run_id = :runId GROUP BY channel_id) ch " +
            "  ON ch.channel_id = rm.channel_id " +
            "JOIN messages m ON m.message_id = rm.message_id JOIN channels c ON c.channel_id = rm.channel_id " +
            "WHERE rm.run_id = :runId AND m.status = 'PENDING' AND m.keep = 0 AND c.state NOT IN ('GONE', 'INACCESSIBLE') " +
            "AND (:dryRun = 0 OR rm.message_id NOT IN (SELECT rr.message_id FROM run_results rr WHERE rr.run_id = :runId)) " +
            "ORDER BY ch.first_position ASC, rm.position DESC LIMIT 1",
    )
    fun nextCandidateOldestFirst(runId: Long, dryRun: Boolean): CandidateRow?

    @Query("SELECT message_id, status, keep FROM messages WHERE message_id = :messageId")
    fun guardRow(messageId: Long): GuardRowView?

    @Query("SELECT EXISTS(SELECT 1 FROM run_messages WHERE run_id = :runId AND message_id = :messageId)")
    fun inRunBlocking(runId: Long, messageId: Long): Boolean

    @Query(
        "UPDATE messages SET status = :status, last_http_status = :httpStatus, last_discord_code = :discordCode, " +
            "attempts = attempts + 1, updated_at = :now WHERE message_id = :messageId",
    )
    fun messageOutcome(messageId: Long, status: MessageStatus, httpStatus: Int?, discordCode: Int?, now: Long)

    @Query("UPDATE channels SET state = :state, last_error = :error WHERE channel_id = :channelId")
    fun channelState(channelId: Long, state: ChannelState, error: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDeleteLog(row: DeleteLogEntity): Long

    @Query("UPDATE runs SET deleted_count = deleted_count + 1 WHERE run_id = :runId")
    fun bumpDeleted(runId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertResult(row: RunResultEntity)

    @Insert
    fun insertEvent(event: EventEntity): Long

    /** The rolling log keeps the newest [keep] rows. */
    @Query("DELETE FROM events WHERE event_id NOT IN (SELECT event_id FROM events ORDER BY event_id DESC LIMIT :keep)")
    fun trimEvents(keep: Int)

    @Query("SELECT COUNT(*) FROM delete_log WHERE deleted_at >= :sinceMs")
    fun deletedSince(sinceMs: Long): Int

    @Query(
        "SELECT ts FROM events WHERE ts >= :sinceMs AND (http_status IN (401, 403) " +
            "OR (http_status = 429 AND action_taken != 'RATE_LIMITED shared')) ORDER BY ts",
    )
    fun invalidResponseTimesSince(sinceMs: Long): List<Long>

    @Query("SELECT run_id, started_at, ended_at, stop_reason FROM runs WHERE run_id = :runId")
    fun snapshot(runId: Long): RunSnapshotRow?

    // An ended run is never written again: a Stop that lands during a request keeps its "stopped".
    @Query("UPDATE runs SET started_at = COALESCE(started_at, :now), stop_reason = NULL WHERE run_id = :runId AND ended_at IS NULL")
    fun runStarted(runId: Long, now: Long)

    @Query("UPDATE runs SET ended_at = :now, stop_reason = :reason WHERE run_id = :runId AND ended_at IS NULL")
    fun runEnded(runId: Long, now: Long, reason: String)

    @Query("UPDATE runs SET stop_reason = :reason WHERE run_id = :runId AND ended_at IS NULL")
    fun runStopReason(runId: Long, reason: String?)

    /** The run that is started and not ended, if any: it must be stopped before another can start (brief decision 1). */
    @Query("SELECT run_id FROM runs WHERE started_at IS NOT NULL AND ended_at IS NULL ORDER BY run_id DESC LIMIT 1")
    fun observeOpenRunId(): Flow<Long?>

    @Query("SELECT run_id FROM runs WHERE started_at IS NOT NULL AND ended_at IS NULL ORDER BY run_id DESC LIMIT 1")
    suspend fun openRunId(): Long?

    @Query("SELECT run_id FROM runs ORDER BY (ended_at IS NULL) DESC, (started_at IS NOT NULL) DESC, run_id DESC LIMIT 1")
    suspend fun currentRunId(): Long?

    @Query("UPDATE run_options SET resume_not_before = :notBefore WHERE run_id = :runId")
    fun resumeNotBefore(runId: Long, notBefore: Long?)

    // ---- The Run screen and the reports ------------------------------------------------------------------------

    /** The run the Run screen shows: an open one (started, not ended) first, then the newest unstarted, then the newest. */
    @Query(
        "SELECT run_id FROM runs ORDER BY (ended_at IS NULL) DESC, (started_at IS NOT NULL) DESC, run_id DESC LIMIT 1",
    )
    fun observeCurrentRunId(): Flow<Long?>

    @Query("$PROGRESS_SELECT WHERE r.run_id = :runId")
    fun observeProgress(runId: Long): Flow<RunProgress?>

    @Query("SELECT * FROM events WHERE run_id = :runId ORDER BY event_id DESC LIMIT :limit")
    fun observeEvents(runId: Long, limit: Int): Flow<List<EventEntity>>

    /** The channel a session would touch next (newest-first order; good enough for the screen). */
    @Query(
        "SELECT c.display_name FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id JOIN channels c ON c.channel_id = rm.channel_id " +
            "WHERE rm.run_id = :runId AND m.status = 'PENDING' AND m.keep = 0 AND c.state NOT IN ('GONE', 'INACCESSIBLE') ORDER BY rm.position ASC LIMIT 1",
    )
    fun observeNextChannelName(runId: Long): Flow<String?>

    @Query(
        "SELECT c.channel_id, c.display_name, c.state, " +
            "COUNT(*) AS total, " +
            "SUM(CASE WHEN m.status = 'DELETED' THEN 1 ELSE 0 END) AS deleted, " +
            "SUM(CASE WHEN m.status = 'PENDING' THEN 1 ELSE 0 END) AS pending, " +
            "SUM(CASE WHEN EXISTS(SELECT 1 FROM run_results rr WHERE rr.run_id = rm.run_id AND rr.message_id = rm.message_id AND rr.verdict = 'WOULD_DELETE') THEN 1 ELSE 0 END) AS dry_would_delete " +
            "FROM run_messages rm JOIN messages m ON m.message_id = rm.message_id JOIN channels c ON c.channel_id = rm.channel_id " +
            "WHERE rm.run_id = :runId GROUP BY c.channel_id ORDER BY MIN(rm.position)",
    )
    fun observeChannelLines(runId: Long): Flow<List<RunChannelLine>>

    @Query("SELECT COUNT(*) FROM delete_log WHERE deleted_at >= :sinceMs")
    fun observeDeletedSince(sinceMs: Long): Flow<Int>

    // ---- The Reports screen. Read-only; the events table holds no secrets and no message text. ------------------

    /** Every run, newest first, with the same counts as the Run screen. */
    @Query("$PROGRESS_SELECT ORDER BY r.run_id DESC")
    fun observeAllProgress(): Flow<List<RunProgress>>

    /** Responses other than 204, grouped by run, HTTP status and Discord code. */
    @Query(
        "SELECT run_id, http_status, discord_code, COUNT(*) AS n FROM events " +
            "WHERE run_id IS NOT NULL AND http_status IS NOT NULL AND http_status != 204 " +
            "GROUP BY run_id, http_status, discord_code ORDER BY run_id DESC, http_status, discord_code",
    )
    fun observeErrorGroups(): Flow<List<RunErrorGroup>>

    /** The events that carry no response: a request that got none, and a retried server or network error. */
    @Query(
        "SELECT run_id, " +
            "SUM(CASE WHEN action_taken = 'NO_RESPONSE' THEN 1 ELSE 0 END) AS no_response_count, " +
            "SUM(CASE WHEN action_taken = 'TRANSIENT' THEN 1 ELSE 0 END) AS transient_count " +
            "FROM events WHERE run_id IS NOT NULL GROUP BY run_id",
    )
    fun observeEventTotals(): Flow<List<RunEventTotals>>

    /** Each run's newest halt event. */
    @Query(
        "SELECT run_id, action_taken FROM events WHERE event_id IN " +
            "(SELECT MAX(event_id) FROM events WHERE run_id IS NOT NULL AND action_taken LIKE 'HALT %' GROUP BY run_id)",
    )
    fun observeLastHalts(): Flow<List<RunLastHalt>>
}
