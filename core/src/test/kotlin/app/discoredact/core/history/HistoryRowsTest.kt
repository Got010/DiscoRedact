package app.discoredact.core.history

import app.discoredact.core.history.Pages.ME
import app.discoredact.core.history.Pages.OTHER
import app.discoredact.core.history.Pages.msg
import app.discoredact.core.model.Snowflake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The author filter (CLAUDE.md rule 4 as amended): only the account's own rows ever leave a page. */
class HistoryRowsTest {
    private fun page(vararg rows: String) = "[${rows.joinToString(",")}]"
    private fun own(body: String, channel: Long = 10) = HistoryRows.own(ME, channel, body) as PageResult.Page

    @Test fun onlyTheAccountsRowsAreKept() {
        val p = own(page(msg(105, ME, 10), msg(104, OTHER, 10), msg(103, ME, 10, attachments = 2), msg(102, 555, 10), msg(101, ME, 10, content = "")))
        assertEquals(listOf(105L, 103L, 101L), p.own.map { it.messageId })
        assertTrue(p.own.all { it.channelId == 10L })
        assertEquals(5, p.pageSize)
        assertEquals(101L, p.oldestId)
        assertEquals(0, p.malformed)
        assertEquals("text 105", p.own[0].content)
        assertFalse(p.own[0].hasAttachments)
        assertTrue(p.own[1].hasAttachments)
        assertEquals("", p.own[2].content)
    }

    @Test fun aPageWithNoneOfTheAccountsRowsKeepsNothingButStillPaginates() {
        val p = own(page(msg(205, OTHER, 10), msg(201, OTHER, 10), msg(203, 77, 10)))
        assertTrue(p.own.isEmpty())
        assertEquals(3, p.pageSize)
        assertEquals(201L, p.oldestId)
    }

    @Test fun anEmptyPage() {
        val p = own("[]")
        assertEquals(0, p.pageSize)
        assertNull(p.oldestId)
        assertTrue(p.own.isEmpty())
    }

    @Test fun malformedRowsAreSkippedAndCounted() {
        val p = own(
            page(
                "42", "null", "\"x\"", "[]",
                """{"type":0,"author":{"id":"$ME"},"content":"no id"}""",
                """{"id":"-5","author":{"id":"$ME"}}""",
                """{"id":"abc","author":{"id":"$ME"}}""",
                """{"id":"300","content":"no author"}""",
                """{"id":"301","author":{"username":"no id"}}""",
                """{"id":"302","author":"$ME"}""",
                """{"id":"303","channel_id":"99","author":{"id":"$ME"},"content":"another channel"}""",
                msg(304, ME, 10, content = "x".repeat(100_001)),
                msg(305, ME, 10),
            ),
        )
        assertEquals(listOf(305L), p.own.map { it.messageId })
        assertEquals(12, p.malformed)
        assertEquals(13, p.pageSize)
        // Every row with a readable id moves `before`, malformed or not.
        assertEquals(300L, p.oldestId)
    }

    @Test fun idsTimestampsAndTheSnowflakeFallback() {
        val id = 1_234_567_890_123_456_789L
        val p = own(
            page(
                msg(id, ME, 10, timestamp = "2021-03-04T12:34:56.789000+00:00"),
                msg(id - 1, ME, 10, timestamp = "2021-03-04 12:34:56.789000+00:00"),
                msg(id - 2, ME, 10, timestamp = null),
                msg(id - 3, ME, 10, timestamp = "yesterday"),
            ),
        )
        assertEquals(1_614_861_296_789L, p.own[0].timestampMillis)
        assertEquals(1_614_861_296_789L, p.own[1].timestampMillis)
        assertEquals(Snowflake.timestampMillis(id - 2), p.own[2].timestampMillis)
        assertEquals(Snowflake.timestampMillis(id - 3), p.own[3].timestampMillis)
        assertEquals(2, p.timestampsFromId)
        assertEquals(id - 3, p.oldestId)
    }

    @Test fun aRowWithoutAChannelIdIsTheChannelRead() {
        val p = own(page("""{"id":"400","author":{"id":"$ME"},"content":"hi","timestamp":"2024-01-01T00:00:00+00:00","attachments":[]}"""))
        assertEquals(listOf(400L), p.own.map { it.messageId })
        assertEquals(10L, p.own[0].channelId)
    }

    @Test fun aBodyThatIsNotAPage() {
        assertEquals(PageResult.Bad(notJson = true), HistoryRows.own(ME, 10, "<!DOCTYPE html><html>cloudflare</html>"))
        assertEquals(PageResult.Bad(notJson = true), HistoryRows.own(ME, 10, "[{\"id\":"))
        assertEquals(PageResult.Bad(notJson = false), HistoryRows.own(ME, 10, """{"message":"x","code":0}"""))
        assertEquals(PageResult.Bad(notJson = false), HistoryRows.own(ME, 10, ""))
        assertEquals(PageResult.Bad(notJson = true), HistoryRows.own(ME, 10, "42"))
        assertEquals(PageResult.Bad(notJson = true), HistoryRows.own(ME, 10, "<html>cf</html>"))
        assertEquals(PageResult.Bad(notJson = true), HistoryRows.own(ME, 10, "[".repeat(100_000)))
    }

    @Test fun aHugePageIsReadInOnePass() {
        // 100 rows of 4,000 characters (Discord's longest message), half of them the account's: about 400 kB.
        val rows = (0 until 100).map { msg(10_000L - it, if (it % 2 == 0) ME else OTHER, 10, content = "é".repeat(4_000)) }
        val p = own(page(*rows.toTypedArray()))
        assertEquals(100, p.pageSize)
        assertEquals(50, p.own.size)
        assertEquals(9_901L, p.oldestId)
        assertTrue(p.own.all { it.content.length == 4_000 })
    }

    @Test fun nothingOfAPageIsPrinted() {
        val p = HistoryRows.own(ME, 10, page(msg(105, ME, 10, content = "SECRET-TEXT-ONE"), msg(104, OTHER, 10, content = "SECRET-TEXT-TWO")))
        val text = p.toString()
        assertFalse(text.contains("SECRET-TEXT"))
        assertFalse(text.contains("105"))
        assertTrue(text.contains("own=1"))
    }

    @Test fun theAccountIdComesOnlyFromTheUserObject() {
        assertEquals(900L, MeParser.id("""{"id":"900","username":"alt"}"""))
        assertNull(MeParser.id("""{"username":"alt"}"""))
        assertNull(MeParser.id("""{"id":"x"}"""))
        assertNull(MeParser.id("""{"id":null}"""))
        assertNull(MeParser.id("""[{"id":"900"}]"""))
        assertNull(MeParser.id("<html></html>"))
        assertNull(MeParser.id(""))
    }
}
