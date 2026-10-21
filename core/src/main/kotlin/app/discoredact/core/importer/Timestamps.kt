package app.discoredact.core.importer

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Parses the package's `Timestamp` column (`2021-03-04 12:34:56.789000+00:00`, or ISO-8601) to epoch millis. */
object Timestamps {
    fun parseMillis(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val iso = if (s.length > 10 && s[10] == ' ') s.substring(0, 10) + "T" + s.substring(11) else s
        return try {
            OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
