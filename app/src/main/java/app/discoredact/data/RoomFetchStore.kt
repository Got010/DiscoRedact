package app.discoredact.data

import app.discoredact.core.history.FetchEventRecord
import app.discoredact.core.history.FetchProgress
import app.discoredact.core.history.FetchSnapshot
import app.discoredact.core.history.FetchStore
import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.importer.ImportedMessage
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.DmMode
import app.discoredact.core.model.MessageStatus
import app.discoredact.core.model.RowSource
import java.util.concurrent.Callable

/**
 * The fetch's store: the progress, channels and messages in Room, the fetch's own state in [state]. Every method
 * commits before it returns; a page's rows and its progress commit together (CLAUDE.md rule 6 for reads).
 */
class RoomFetchStore(private val db: AppDatabase, private val state: FetchStateStore) : FetchStore {
    private val fetches = db.fetches()
    private val channels = db.channels()
    private val messages = db.messages()
    private val runs = db.runs()
    private var eventsSinceTrim = 0

    override fun fetch(): FetchSnapshot? = state.snapshot()
    override fun fetchStarted(now: Long) = state.started(now)
    override fun fetchEnded(now: Long, reason: String) = state.ended(now, reason)
    override fun fetchPaused(reason: String, resumeNotBefore: Long?) = state.paused(reason, resumeNotBefore)
    override fun accountId(id: Long, capturedAt: Long) = state.account(id, capturedAt)

    override fun progress(channelId: Long): FetchProgress? = fetches.progress(channelId)?.toCore()
    override fun nextChannel(): FetchProgress? = fetches.nextChannel()?.toCore()

    override fun channelsSeen(channels: List<ImportedChannel>, now: Long) {
        db.runInTransaction(
            Runnable {
                val ids = this.channels.insertIgnore(
                    channels.map {
                        ChannelEntity(
                            channelId = it.channelId, type = it.type, displayName = it.displayName,
                            recipientIds = it.recipientIds.joinToString(","), messageCount = 0,
                            mode = DmMode.DEFAULT, state = ChannelState.PENDING, lastError = null, source = RowSource.API,
                        )
                    },
                )
                val present = channels.filterIndexed { i, _ -> ids[i] == -1L }
                present.map { it.channelId }.chunked(CHUNK).forEach { this.channels.markBoth(it, RowSource.PACKAGE) }
                for (c in present) fetches.refreshFetchedNames(c.channelId, c.displayName, c.recipientIds.joinToString(","))
                fetches.insertProgress(channels.map { FetchProgressEntity(it.channelId, null, false, now) })
            },
        )
        state.listed(now)
    }

    override fun upsertOwn(messages: List<ImportedMessage>, now: Long): Int {
        if (messages.isEmpty()) return 0
        val ids = this.messages.insertIgnore(
            messages.map {
                MessageEntity(
                    messageId = it.messageId, channelId = it.channelId, timestamp = it.timestampMillis,
                    content = it.content, hasAttachments = it.hasAttachments, selected = false, keep = false,
                    status = MessageStatus.PENDING, attempts = 0, lastHttpStatus = null, lastDiscordCode = null,
                    updatedAt = now, source = RowSource.API,
                )
            },
        )
        val present = messages.filterIndexed { i, _ -> ids[i] == -1L }.map { it.messageId }
        present.chunked(CHUNK).forEach { this.messages.markBoth(it, RowSource.PACKAGE) }
        return messages.size - present.size
    }

    override fun pageDone(channelId: Long, oldestId: Long?, done: Boolean, now: Long) {
        fetches.pageDone(channelId, oldestId, done, now)
        fetches.recount(channelId)
    }

    override fun pageWritten(channelId: Long, own: List<ImportedMessage>, oldestId: Long?, done: Boolean, now: Long): Int {
        val inserted = db.runInTransaction(
            Callable {
                val n = upsertOwn(own, now)
                pageDone(channelId, oldestId, done, now)
                n
            },
        )
        state.addFound(own.size)
        return inserted
    }

    override fun channelOutcome(channelId: Long, state: ChannelState, error: String, now: Long) {
        db.runInTransaction(
            Runnable {
                runs.channelState(channelId, state, error)
                fetches.markDone(channelId, now)
            },
        )
    }

    override fun event(event: FetchEventRecord) {
        runs.insertEvent(
            EventEntity(
                ts = event.ts, runId = null, httpStatus = event.httpStatus, discordCode = event.discordCode,
                bucket = event.bucket, remaining = event.remaining, resetAfter = event.resetAfter, actionTaken = event.actionTaken,
            ),
        )
        if (++eventsSinceTrim >= TRIM_EVERY) {
            eventsSinceTrim = 0
            runs.trimEvents(RoomRunStore.KEEP_EVENTS)
        }
    }

    override fun readSent(now: Long, dayStartMs: Long) = state.readSent(dayStartMs)
    override fun readsOn(dayStartMs: Long): Int = state.readsOn(dayStartMs)
    override fun deletedSince(sinceMs: Long): Int = runs.deletedSince(sinceMs)
    override fun invalidResponseTimesSince(sinceMs: Long): List<Long> = runs.invalidResponseTimesSince(sinceMs)

    private companion object {
        const val CHUNK = 500
        const val TRIM_EVERY = 200
    }
}
