package app.discoredact.core.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LeftoversCsvTest {
    @Test
    fun anEmptyListGivesTheHeaderOnly() {
        assertEquals("channel_id,message_id\r\n", LeftoversCsv.render(emptyList()))
    }

    @Test
    fun oneRowPerPairWithCrlfLineEndsAndNoOtherColumn() {
        val text = LeftoversCsv.render(listOf(10L to 100L, 10L to 101L))
        assertEquals("channel_id,message_id\r\n10,100\r\n10,101\r\n", text)
        assertFalse(text.replace("\r\n", "").contains('\n'))
        assertFalse(text.replace("\r\n", "").contains('\r'))
    }

    @Test
    fun rowsAreOrderedByChannelThenMessageWhateverTheInputOrder() {
        val text = LeftoversCsv.render(listOf(20L to 5L, 10L to 9L, 20L to 3L, 10L to 2L))
        assertEquals(listOf("channel_id,message_id", "10,2", "10,9", "20,3", "20,5"), text.split("\r\n").dropLast(1))
    }

    @Test
    fun idsAreOrderedAsNumbersNotAsText() {
        val text = LeftoversCsv.render(listOf(9L to 1L, 10L to 1L, 100L to 1L))
        assertEquals(listOf("9,1", "10,1", "100,1"), text.split("\r\n").drop(1).dropLast(1))
    }

    @Test
    fun idsArePrintedAsPlainDecimalWithNoExponentOrGrouping() {
        val big = 1_187_654_321_098_765_432L
        val text = LeftoversCsv.render(listOf(big to Long.MAX_VALUE))
        assertEquals("channel_id,message_id\r\n1187654321098765432,9223372036854775807\r\n", text)
        val row = text.split("\r\n")[1]
        assertTrue(row.all { it.isDigit() || it == ',' })
    }

    @Test
    fun theBytesAreUtf8WithoutAByteOrderMarkAndEndWithCrlf() {
        val bytes = LeftoversCsv.toBytes(listOf(1L to 2L))
        assertArrayEquals("channel_id,message_id\r\n1,2\r\n".toByteArray(Charsets.UTF_8), bytes)
        assertEquals('c'.code, bytes[0].toInt())
        assertTrue(bytes.takeLast(2) == listOf('\r'.code.toByte(), '\n'.code.toByte()))
    }

    @Test
    fun theSuggestedNameCarriesTheLocalDateAsYyyymmdd() {
        assertEquals("discoredact-leftovers-20261023.csv", LeftoversCsv.suggestedName(LocalDate.of(2026, 10, 23)))
        assertEquals("discoredact-leftovers-20260105.csv", LeftoversCsv.suggestedName(LocalDate.of(2026, 1, 5)))
    }
}
