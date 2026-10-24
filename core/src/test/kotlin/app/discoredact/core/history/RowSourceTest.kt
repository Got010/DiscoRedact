package app.discoredact.core.history

import app.discoredact.core.model.RowSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dedupe of the two imports, both ways (B34 API history, section 5). */
class RowSourceTest {
    @Test fun aRowFoundByBothIsBothWhicheverCameFirst() {
        assertEquals(RowSource.BOTH, RowSource.PACKAGE.seenBy(RowSource.API))
        assertEquals(RowSource.BOTH, RowSource.API.seenBy(RowSource.PACKAGE))
        assertEquals(RowSource.PACKAGE, RowSource.PACKAGE.seenBy(RowSource.PACKAGE))
        assertEquals(RowSource.API, RowSource.API.seenBy(RowSource.API))
        for (s in RowSource.entries) assertEquals(RowSource.BOTH, RowSource.BOTH.seenBy(s))
    }
}
