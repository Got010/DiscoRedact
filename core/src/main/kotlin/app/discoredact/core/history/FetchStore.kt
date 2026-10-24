package app.discoredact.core.history

import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.importer.ImportedMessage
import app.discoredact.core.model.ChannelState

/** How the current fetch reads at a point in time. Nothing here is a secret; the account id is still never printed. */
data class FetchSnapshot(
    val startedAt: Long?,
    val endedAt: Long?,
    val stopReason: String?,
    val resumeNotBefore: Long?,
    /** The account's id from `GET /users/@me`, once it answered. */
    val accountId: Long?,
    /** The `captured_at` of the header set [accountId] was read with: another capture means asking again. */
    val accountCapturedAt: Long?,
    /** When the DM list was read and written; null until then. */
    val listedAt: Long?,
) {
    override fun toString(): String =
        "FetchSnapshot(startedAt=$startedAt, endedAt=$endedAt, stopReason=$stopReason, account=${if (accountId == null) "unknown" else "known"}, listedAt=$listedAt)"
}

/** One row of the rolling event log for a read: the same columns as a run's, with no run. No header, no body text. */
data class FetchEventRecord(
    val ts: Long,
    val httpStatus: Int?,
    val discordCode: Int?,
    val bucket: String?,
    val remaining: Int?,
    val resetAfter: Double?,
    val actionTaken: String,
)

/**
 * Everything the fetch reads and writes (the read side's `RunStore`). The app implements it on Room and the settings
 * file; tests use an in-memory one. Every write is committed before the session moves on, so a kill or reboot resumes
 * from the rows: at most the page in flight is read again (its rows are already present then), none is skipped.
 */
interface FetchStore {
    /** The current fetch, or null when none was ever started (or everything was wiped). */
    fun fetch(): FetchSnapshot?

    /** Sets `started_at` when it is null and clears a pause or halt reason and its hold. */
    fun fetchStarted(now: Long)

    /** Ends the fetch: `ended_at` and the reason. */
    fun fetchEnded(now: Long, reason: String)

    /** A pause or halt: the reason set, `ended_at` left null so it can be resumed; [resumeNotBefore] for edge blocks. */
    fun fetchPaused(reason: String, resumeNotBefore: Long?)

    /** Records the account id `GET /users/@me` answered with, and the capture it was read with. */
    fun accountId(id: Long, capturedAt: Long)

    fun progress(channelId: Long): FetchProgress?

    /** The first listed DM that is not done, in channel-id order; null when none is left. */
    fun nextChannel(): FetchProgress?

    /**
     * Writes the DM list in one transaction: each channel inserted with source API (a row already there from the
     * package becomes BOTH; mode, state and selections untouched), a progress row per DM, and `listedAt`.
     */
    fun channelsSeen(channels: List<ImportedChannel>, now: Long)

    /**
     * Inserts the account's own messages not yet present with source API, `selected` false, `keep` false, status
     * PENDING; a message already present from the package becomes BOTH and nothing else of it changes. Returns how
     * many rows were new.
     */
    fun upsertOwn(messages: List<ImportedMessage>, now: Long): Int

    /** A page's progress: the DM's oldest id read so far and whether it is done. */
    fun pageDone(channelId: Long, oldestId: Long?, done: Boolean, now: Long)

    /** A page's rows and its progress, as one unit: the app does both in one transaction. Returns the new rows. */
    fun pageWritten(channelId: Long, own: List<ImportedMessage>, oldestId: Long?, done: Boolean, now: Long): Int {
        val inserted = upsertOwn(own, now)
        pageDone(channelId, oldestId, done, now)
        return inserted
    }

    /**
     * A DM the history read found gone (404) or inaccessible (403): the channel's state, and its progress done. Like a
     * delete's 403 or 404, this takes the DM out of later runs until a read of it succeeds ([channelReadable]).
     */
    fun channelOutcome(channelId: Long, state: ChannelState, error: String, now: Long)

    /**
     * A page of the DM was just read: if a 403 or 404 (a read's or a delete's) marked it INACCESSIBLE or GONE, it is
     * PENDING again with no error, since the read proves access. Any other state is left alone.
     */
    fun channelReadable(channelId: Long, now: Long)

    fun event(event: FetchEventRecord)

    /** Counts one read sent on the local day starting at [dayStartMs] (the daily cap counts reads and deletes). */
    fun readSent(now: Long, dayStartMs: Long)

    /** Reads sent on the local day starting at [dayStartMs]. */
    fun readsOn(dayStartMs: Long): Int

    /** Real deletions recorded at or after [sinceMs]. */
    fun deletedSince(sinceMs: Long): Int

    /** Timestamps of the invalid responses (401, 403, 429 not shared) of runs and fetches, to seed the tripwire. */
    fun invalidResponseTimesSince(sinceMs: Long): List<Long>
}
