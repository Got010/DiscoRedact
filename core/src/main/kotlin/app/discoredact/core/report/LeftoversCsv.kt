package app.discoredact.core.report

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The leftovers export (spec section 10): the IDs of messages the app could not delete, as `channel_id,message_id`
 * and nothing else. No text, no timestamps, no account data. CRLF line ends, UTF-8 without a byte-order mark.
 */
object LeftoversCsv {
    const val HEADER = "channel_id,message_id"
    private const val EOL = "\r\n"

    /** One line per (channelId, messageId) pair, ordered by channel then message id; an empty list gives the header only. */
    fun render(pairs: List<Pair<Long, Long>>): String {
        val sb = StringBuilder(HEADER.length + EOL.length + pairs.size * 40)
        sb.append(HEADER).append(EOL)
        for ((channelId, messageId) in pairs.sortedWith(compareBy({ it.first }, { it.second }))) {
            sb.append(channelId.toString()).append(',').append(messageId.toString()).append(EOL)
        }
        return sb.toString()
    }

    /** [render] as UTF-8 bytes. The text is ASCII, so there is no byte-order mark and no encoding to get wrong. */
    fun toBytes(pairs: List<Pair<Long, Long>>): ByteArray = render(pairs).toByteArray(Charsets.UTF_8)

    /** The file name offered to the system's file picker: `discoredact-leftovers-<yyyyMMdd>.csv`. */
    fun suggestedName(date: LocalDate): String = "discoredact-leftovers-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.csv"
}
