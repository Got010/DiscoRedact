package app.discoredact.core.importer

import java.io.Reader

/**
 * Streaming RFC 4180 reader: quoted fields, `""` escapes, commas and newlines inside quotes, CRLF/LF/CR line ends,
 * a leading BOM. One record at a time; never holds more than one record in memory.
 */
class CsvReader(private val reader: Reader, private val maxFieldChars: Int = 100_000) {
    sealed interface Record {
        data class Row(val fields: List<String>) : Record

        /** A record that could not be read (an over-long field, or a quote left open at the end of the file). */
        data object Malformed : Record
    }

    private var pushback = -2
    private var atStart = true
    private var eof = false

    private fun read(): Int {
        if (pushback != -2) {
            val c = pushback
            pushback = -2
            return c
        }
        var c = reader.read()
        if (atStart) {
            atStart = false
            if (c == 0xFEFF) c = reader.read()
        }
        return c
    }

    /** The next record, or null at the end of input. */
    fun next(): Record? {
        if (eof) return null
        val fields = ArrayList<String>(4)
        val field = StringBuilder()
        var inQuotes = false
        var quotedField = false
        var tooLong = false
        var sawAny = false
        while (true) {
            val c = read()
            if (c == -1) {
                eof = true
                if (inQuotes) return Record.Malformed
                if (!sawAny) return null
                fields.add(field.toString())
                return if (tooLong) Record.Malformed else Record.Row(fields)
            }
            sawAny = true
            val ch = c.toChar()
            if (inQuotes) {
                if (ch == '"') {
                    val n = read()
                    if (n == '"'.code) append(field, '"').also { if (!it) tooLong = true } else {
                        inQuotes = false
                        if (n != -1) pushback = n
                    }
                } else if (!append(field, ch)) {
                    tooLong = true
                }
                continue
            }
            when (ch) {
                '"' -> if (field.isEmpty() && !quotedField) {
                    inQuotes = true
                    quotedField = true
                } else if (!append(field, ch)) {
                    tooLong = true
                }
                ',' -> {
                    fields.add(field.toString())
                    field.setLength(0)
                    quotedField = false
                }
                '\r', '\n' -> {
                    if (ch == '\r') {
                        val n = read()
                        if (n != '\n'.code && n != -1) pushback = n
                    }
                    fields.add(field.toString())
                    return if (tooLong) Record.Malformed else Record.Row(fields)
                }
                else -> if (!append(field, ch)) tooLong = true
            }
        }
    }

    private fun append(sb: StringBuilder, ch: Char): Boolean {
        if (sb.length >= maxFieldChars) return false
        sb.append(ch)
        return true
    }
}
