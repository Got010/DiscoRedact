package app.discoredact.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class CsvReaderTest {
    private fun all(text: String, max: Int = 100): List<CsvReader.Record> {
        val r = CsvReader(StringReader(text), max)
        return generateSequence { r.next() }.toList()
    }

    @Test
    fun readsQuotedFieldsAndLineEnds() {
        val rows = all("﻿a,b\r\n\"x,1\",\"y\r\nz\"\r\"q\"\"\",\n")
        assertEquals(
            listOf(
                CsvReader.Record.Row(listOf("a", "b")),
                CsvReader.Record.Row(listOf("x,1", "y\r\nz")),
                CsvReader.Record.Row(listOf("q\"", "")),
            ),
            rows,
        )
    }

    @Test
    fun overLongFieldAndOpenQuoteAreMalformed() {
        val rows = all("1,${"x".repeat(20)}\n2,ok\n3,\"open", max = 10)
        assertEquals(CsvReader.Record.Malformed, rows[0])
        assertEquals(CsvReader.Record.Row(listOf("2", "ok")), rows[1])
        assertEquals(CsvReader.Record.Malformed, rows[2])
        assertEquals(3, rows.size)
    }

    @Test
    fun emptyInputHasNoRecords() {
        assertNull(CsvReader(StringReader("")).next())
    }
}
