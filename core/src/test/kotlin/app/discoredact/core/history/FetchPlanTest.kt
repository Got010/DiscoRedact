package app.discoredact.core.history

import app.discoredact.core.executor.GetRequest
import app.discoredact.core.history.Pages.ME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pagination (B34 API history, section 3). */
class FetchPlanTest {
    private fun page(size: Int, oldest: Long?) = PageResult.Page(emptyList(), size, oldest, 0, 0)

    @Test fun theFirstPageHasNoBeforeAndTheNextUsesTheSmallestIdOfThePrevious() {
        assertEquals(GetRequest.Messages(10, null), FetchPlan.request(FetchProgress(10, null, false)))
        val body = Pages.page(Pages.pageRows(10, 5_000, 100)).body
        val p = HistoryRows.own(ME, 10, body) as PageResult.Page
        val next = FetchPlan.advance(10, null, p) as FetchPlan.Advance.Next
        assertEquals(FetchProgress(10, 4_901, false), next.progress)
        assertEquals(GetRequest.Messages(10, 4_901), FetchPlan.request(next.progress))
    }

    @Test fun aPageUnderOneHundredEndsTheDm() {
        val next = FetchPlan.advance(10, 4_901, page(99, 4_802)) as FetchPlan.Advance.Next
        assertEquals(FetchProgress(10, 4_802, true), next.progress)
        assertNull(FetchPlan.request(next.progress))
    }

    @Test fun anEmptyDmEndsAtItsFirstPage() {
        val next = FetchPlan.advance(10, null, page(0, null)) as FetchPlan.Advance.Next
        assertEquals(FetchProgress(10, null, true), next.progress)
    }

    @Test fun anEmptyPageAfterAFullOneKeepsTheOldestIdAndEnds() {
        val next = FetchPlan.advance(10, 4_901, page(0, null)) as FetchPlan.Advance.Next
        assertEquals(FetchProgress(10, 4_901, true), next.progress)
    }

    @Test fun aShortFirstPageEndsTheDm() {
        val next = FetchPlan.advance(10, null, page(3, 77)) as FetchPlan.Advance.Next
        assertEquals(FetchProgress(10, 77, true), next.progress)
    }

    @Test fun aFullPageThatCannotMoveBackIsStuck() {
        assertEquals(FetchPlan.Advance.Stuck, FetchPlan.advance(10, 4_901, page(100, null)))
        assertEquals(FetchPlan.Advance.Stuck, FetchPlan.advance(10, 4_901, page(100, 4_901)))
        assertEquals(FetchPlan.Advance.Stuck, FetchPlan.advance(10, 4_901, page(100, 5_000)))
    }
}
