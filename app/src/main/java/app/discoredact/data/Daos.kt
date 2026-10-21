package app.discoredact.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.discoredact.core.model.DmMode
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

    @Query("INSERT OR REPLACE INTO selection_overrides (message_id, selected) SELECT message_id, :selected FROM messages WHERE message_id IN (:ids)")
    suspend fun setOverrides(ids: List<Long>, selected: Boolean)

    @Query("UPDATE messages SET content = NULL")
    suspend fun purgeContent()

    @Query("SELECT COUNT(*) FROM messages WHERE content IS NOT NULL")
    fun observeContentCount(): Flow<Int>
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

    @Query("SELECT COUNT(*) FROM runs")
    fun observeRunCount(): Flow<Int>
}
