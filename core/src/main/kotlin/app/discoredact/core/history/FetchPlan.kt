package app.discoredact.core.history

import app.discoredact.core.executor.GetRequest

/** A DM's read progress: `fetch_progress` (schema 3). [oldestFetchedId] is the next page's `before`. */
data class FetchProgress(val channelId: Long, val oldestFetchedId: Long?, val done: Boolean)

/**
 * Pagination (B34 API history, section 3): `limit=100`, then `before` = the smallest id of the previous page, until a page
 * has fewer than 100 rows. An empty first page ends the DM. The next page is never asked for before the previous
 * page's progress is written (the session writes it under the send lock before it lets go).
 */
object FetchPlan {
    /** The read for [progress]'s DM, or null when it is done. */
    fun request(progress: FetchProgress): GetRequest.Messages? =
        if (progress.done) null else GetRequest.Messages(progress.channelId, progress.oldestFetchedId)

    sealed interface Advance {
        /** Write this progress; the DM is finished when [FetchProgress.done]. */
        data class Next(val progress: FetchProgress) : Advance

        /**
         * A full page that cannot move `before` further back (no readable id, or not older than the page asked
         * for): reading on would repeat a page forever, so the fetch halts instead.
         */
        data object Stuck : Advance
    }

    /** The progress after [page], read with `before` = [asked]. */
    fun advance(channelId: Long, asked: Long?, page: PageResult.Page): Advance {
        val done = page.pageSize < GetRequest.PAGE_LIMIT
        val oldest = page.oldestId
        if (!done && (oldest == null || (asked != null && oldest >= asked))) return Advance.Stuck
        return Advance.Next(FetchProgress(channelId, oldest ?: asked, done))
    }
}
