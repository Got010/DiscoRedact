package app.discoredact.data

import androidx.room.withTransaction
import app.discoredact.core.importer.ImportProgress
import app.discoredact.core.importer.ImportReport
import app.discoredact.core.importer.ImportSink
import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.importer.ImportedMessage
import app.discoredact.core.importer.PackageImporter
import app.discoredact.core.importer.ZipSource
import app.discoredact.core.executor.Executor
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.selection.DeletePlan
import app.discoredact.core.selection.DeletePlanBuilder
import app.discoredact.core.selection.MessageFilter
import app.discoredact.core.selection.RunRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import app.discoredact.core.selection.SelectionRules

/** Counts the filter screen shows live, before anything is applied. */
data class FilterPreview(
    val inScope: Int,
    val matches: Int,
    val matchesKept: Int,
    val manualOverrides: Int,
    val selectedNow: Int,
    val selectedAfterApply: Int,
)

sealed interface FreezeResult {
    data class Frozen(val runId: Long, val total: Int, val channels: Int) : FreezeResult

    /** The selection changed between computing the review and confirming it; nothing was frozen. */
    data class Changed(val current: DeletePlan) : FreezeResult
    data object Empty : FreezeResult
}

/** Glue between Room and the plain-Kotlin rules in :core. Every selection decision is made by :core. */
class Repository(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val channels = db.channels()
    private val messages = db.messages()
    private val runs = db.runs()

    // ---- Import -------------------------------------------------------------------------------------------------

    /**
     * Streams the package into Room in batches. Re-importing adds new messages only; selections are kept.
     * [checkCancelled] runs before every batch write and throws to stop the import; the app passes a StopSignal that
     * only Wipe everything trips. Rows already written stay, as after any interrupted import, and the message counts
     * are recounted in every case.
     */
    fun import(source: ZipSource, checkCancelled: () -> Unit = {}, progressCallback: (ImportProgress) -> Unit): ImportReport {
        val sink = object : ImportSink {
            override fun onChannels(channels: List<ImportedChannel>) {
                db.runInTransaction(Runnable {
                    this@Repository.channels.insertIgnore(
                        channels.map {
                            ChannelEntity(
                                channelId = it.channelId, type = it.type, displayName = it.displayName,
                                recipientIds = it.recipientIds.joinToString(","), messageCount = 0,
                                mode = DmMode.DEFAULT, state = ChannelState.PENDING, lastError = null,
                            )
                        },
                    )
                    channels.forEach { this@Repository.channels.updateNames(it.channelId, it.displayName, it.recipientIds.joinToString(",")) }
                })
            }

            override fun onMessages(batch: List<ImportedMessage>): Int {
                checkCancelled()
                val now = clock()
                var inserted = 0
                db.runInTransaction(Runnable {
                    val ids = messages.insertIgnore(
                        batch.map {
                            MessageEntity(
                                messageId = it.messageId, channelId = it.channelId, timestamp = it.timestampMillis,
                                content = it.content, hasAttachments = it.hasAttachments, selected = false, keep = false,
                                status = MessageStatus.PENDING, attempts = 0, lastHttpStatus = null,
                                lastDiscordCode = null, updatedAt = now,
                            )
                        },
                    )
                    inserted = ids.count { it != -1L }
                })
                return inserted
            }

            override fun onProgress(progress: ImportProgress) = progressCallback(progress)
        }
        var failure: Throwable? = null
        return try {
            PackageImporter().import(source, sink)
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // Counts match the rows actually written, whether the import finished, failed or was stopped.
            try {
                db.runInTransaction(Runnable { channels.recountMessages() })
            } catch (e: Exception) {
                failure?.addSuppressed(e) ?: throw e
            }
        }
    }

    // ---- Channels and browser -----------------------------------------------------------------------------------

    fun observeSummaries() = channels.observeSummaries()
    fun observeSummary(id: Long) = channels.observeSummary(id)
    fun observeChannelCount() = channels.observeCount()
    fun observeContentCount() = messages.observeContentCount()
    fun observeRunCount() = runs.observeRunCount()
    suspend fun setMode(id: Long, mode: DmMode) = channels.setMode(id, mode)
    suspend fun setIncluded(id: Long, included: Boolean) = channels.setIncluded(id, included)
    suspend fun setAllIncluded(included: Boolean) = channels.setAllIncluded(included)

    fun observeMessageCount(channelId: Long, query: String?) = messages.observeCount(channelId, likeArg(query))
    suspend fun page(channelId: Long, query: String?, limit: Int, offset: Int) = messages.page(channelId, likeArg(query), limit, offset)

    /** A tap: flips `selected` and records the manual toggle (SelectionRules.manualToggle). */
    suspend fun toggleSelected(row: MessageRow) {
        val next = SelectionRules.manualToggle(row.toSel())
        setSelectedManually(listOf(row.messageId), next.selected)
    }

    /** Multi-select mark/unmark: sets `selected` by hand on every ID (SelectionRules.manualSet). */
    suspend fun setSelectedManually(ids: List<Long>, selected: Boolean) {
        val now = clock()
        db.withTransaction {
            ids.chunked(CHUNK).forEach {
                messages.setSelected(it, selected, now)
                messages.setOverrides(it, selected)
            }
        }
    }

    /** The Keep action. Touches only `keep` (SelectionRules.setKeep). */
    suspend fun setKeep(ids: List<Long>, keep: Boolean) {
        val now = clock()
        db.withTransaction { ids.chunked(CHUNK).forEach { messages.setKeep(it, keep, now) } }
    }

    // ---- Filters ------------------------------------------------------------------------------------------------

    /** Streams [channelIds] newest first through [block], a page at a time. */
    private suspend fun forEachRow(channelIds: List<Long>, block: suspend (List<MessageRow>) -> Unit) {
        for (id in channelIds) {
            var beforeTs = Long.MAX_VALUE
            var beforeId = Long.MAX_VALUE
            while (true) {
                val page = messages.keysetPage(id, beforeTs, beforeId, PAGE)
                if (page.isEmpty()) break
                block(page)
                beforeTs = page.last().timestamp
                beforeId = page.last().messageId
                if (page.size < PAGE) break
            }
        }
    }

    suspend fun previewFilter(channelIds: List<Long>, filter: MessageFilter): FilterPreview {
        var inScope = 0
        var matches = 0
        var kept = 0
        var manual = 0
        var selectedNow = 0
        var after = 0
        forEachRow(channelIds) { page ->
            for (r in page) {
                val m = r.toSel()
                inScope++
                if (m.selected) selectedNow++
                if (m.manualSelected != null) manual++
                if (!filter.isEmpty) {
                    if (filter.matches(m)) {
                        matches++
                        if (m.keep) kept++
                    }
                    if (SelectionRules.selectedAfterFilter(m, filter)) after++
                }
            }
        }
        return FilterPreview(inScope, matches, kept, manual, selectedNow, if (filter.isEmpty) selectedNow else after)
    }

    /** Applies a filter in scope: only `selected` changes, manual toggles win, `keep` is never touched. */
    suspend fun applyFilter(channelIds: List<Long>, filter: MessageFilter): Int {
        require(!filter.isEmpty)
        return rewriteSelected(channelIds) { SelectionRules.selectedAfterFilter(it.toSel(), filter) }
    }

    /** Clears filter selections in scope. Manual toggles stay (precedence: manual > filter). */
    suspend fun clearFilterSelection(channelIds: List<Long>): Int =
        rewriteSelected(channelIds) { it.manual ?: false }

    private suspend fun rewriteSelected(channelIds: List<Long>, decide: (MessageRow) -> Boolean): Int {
        val toSelect = ArrayList<Long>()
        val toClear = ArrayList<Long>()
        forEachRow(channelIds) { page ->
            for (r in page) {
                val want = decide(r)
                if (want != r.selected) (if (want) toSelect else toClear).add(r.messageId)
            }
        }
        val now = clock()
        db.withTransaction {
            toSelect.chunked(CHUNK).forEach { messages.setSelectedByFilter(it, true, now) }
            toClear.chunked(CHUNK).forEach { messages.setSelectedByFilter(it, false, now) }
        }
        return toSelect.size + toClear.size
    }

    // ---- Final review and freeze --------------------------------------------------------------------------------

    /** The delete set as :core computes it now: eligible channels in list order, newest first within each. */
    suspend fun computePlan(): DeletePlan {
        val builder = DeletePlanBuilder()
        for (c in channels.summaries()) {
            val sel = c.toSel()
            builder.startChannel(sel)
            if (sel.eligible) {
                var beforeTs = Long.MAX_VALUE
                var beforeId = Long.MAX_VALUE
                while (true) {
                    val page = messages.keysetPageNoContent(c.channelId, beforeTs, beforeId, PAGE)
                    if (page.isEmpty()) break
                    page.forEach { builder.offer(it.toSel()) }
                    beforeTs = page.last().timestamp
                    beforeId = page.last().messageId
                    if (page.size < PAGE) break
                }
            }
            builder.finishChannel()
        }
        return builder.build()
    }

    suspend fun rowsAt(plan: DeletePlan, from: Int, count: Int): List<MessageRow> {
        val ids = (from until minOf(plan.total, from + count)).map { plan.idAt(it) }
        if (ids.isEmpty()) return emptyList()
        val byId = messages.rowsByIds(ids).associateBy { it.messageId }
        return ids.mapNotNull { byId[it] }
    }

    // ---- Runs (M2) ----------------------------------------------------------------------------------------------

    fun observeCurrentRunId() = runs.observeCurrentRunId()
    fun observeProgress(runId: Long) = runs.observeProgress(runId)
    fun observeEvents(runId: Long, limit: Int) = runs.observeEvents(runId, limit)
    fun observeNextChannelName(runId: Long) = runs.observeNextChannelName(runId)
    fun observeChannelLines(runId: Long) = runs.observeChannelLines(runId)
    fun observeDeletedSince(sinceMs: Long) = runs.observeDeletedSince(sinceMs)

    // ---- Reports (M4). Read-only. -------------------------------------------------------------------------------

    fun observeStatusCounts() = messages.observeStatusCounts()
    fun observeKeptCount() = messages.observeKeptCount()
    fun observeLeftoverCount() = messages.observeLeftoverCount()
    suspend fun leftovers(): List<LeftoverRow> = messages.leftovers()

    /** Every run, newest first, with its counts and its errors. */
    fun observeRunReports(): Flow<List<RunReport>> = combine(
        runs.observeAllProgress(),
        runs.observeErrorGroups(),
        runs.observeEventTotals(),
        runs.observeLastHalts(),
    ) { progress, errors, totals, halts -> assembleRunReports(progress, errors, totals, halts) }

    fun runOptions(runId: Long): RunOptionsEntity? = runs.options(runId)
    fun observeOpenRunId() = runs.observeOpenRunId()
    suspend fun openRunId(): Long? = runs.openRunId()
    suspend fun currentRunId(): Long? = runs.currentRunId()
    fun runSnapshot(runId: Long): RunSnapshotRow? = runs.snapshot(runId)

    /** Records how a frozen run is to be executed, once: the first start writes it, a resume keeps it. */
    suspend fun prepareRun(runId: Long, dryRun: Boolean, oldestFirst: Boolean): RunOptionsEntity = db.withTransaction {
        runs.options(runId) ?: RunOptionsEntity(runId, dryRun, oldestFirst, null, clock()).also { runs.insertOptions(it) }
    }

    /** The user's Stop: the run ends now; the caller cancels the worker. */
    suspend fun stopRun(runId: Long) = runs.runEnded(runId, clock(), Executor.STOP_STOPPED)

    /** The user's Pause: the caller cancels the worker; the run stays open with this reason. */
    suspend fun pauseRun(runId: Long) = runs.runStopReason(runId, Executor.STOP_PAUSED_USER)

    /** The worker ended for quiet hours or the daily cap; the next session is enqueued for [untilMs]. */
    fun markWaiting(runId: Long, reason: String, untilMs: Long) = db.runInTransaction(
        Runnable {
            runs.runStopReason(runId, reason)
            runs.resumeNotBefore(runId, untilMs)
        },
    )

    /** A delayed worker fired while the app was closed and could not start its foreground service. */
    fun markWaitingForApp(runId: Long) = runs.runStopReason(runId, Executor.STOP_WAITING_APP)

    /**
     * Freezes the reviewed delete set into a new run. The set is recomputed inside the transaction; if it differs
     * from what the user reviewed, nothing is written and the new set is returned for a fresh review.
     */
    suspend fun freeze(reviewed: DeletePlan): FreezeResult = db.withTransaction {
        val now = computePlan()
        when {
            !now.sameSetAs(reviewed) -> FreezeResult.Changed(now)
            now.total == 0 -> FreezeResult.Empty
            else -> {
                // The newest unstarted run is the only runnable one (RunRules.runnable; the M2 executor relies on
                // it): every run frozen earlier and never started is closed as superseded before the new one.
                val stale = RunRules.toSupersede(runs.runStates().map { it.toRow() })
                if (stale.isNotEmpty()) runs.close(stale, clock(), RunRules.SUPERSEDED)
                val runId = runs.insertRun(RunEntity(startedAt = null, endedAt = null, deletedCount = 0, stopReason = null))
                val ids = now.messageIds()
                ids.indices.chunked(CHUNK).forEach { idx ->
                    runs.insertRunMessages(idx.map { i -> RunMessageEntity(runId, ids[i], now.channelAt(i), i) })
                }
                check(runs.runSize(runId) == now.total) { "frozen set size mismatch" }
                FreezeResult.Frozen(runId, now.total, now.channels.size)
            }
        }
    }

    // ---- Settings actions ---------------------------------------------------------------------------------------

    /**
     * Clears every message's text, then checkpoints the WAL and vacuums (outside any transaction) so the old text
     * leaves the write-ahead log and the freed pages of the database file.
     */
    suspend fun purgeContent() {
        messages.purgeContent()
        withContext(Dispatchers.IO) {
            val sql = db.openHelper.writableDatabase
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sql.execSQL("VACUUM")
            sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
    }

    companion object {
        private const val CHUNK = 500
        private const val PAGE = 2_000

        /** Escapes a search string for `LIKE ... ESCAPE '\'`; blank means no search. */
        fun likeArg(query: String?): String? {
            val q = query?.trim().orEmpty()
            if (q.isEmpty()) return null
            return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        }
    }
}
