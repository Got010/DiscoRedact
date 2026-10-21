package app.discoredact.core.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds synthetic data packages in memory. Every ID and text here is made up; no real Discord data. */
object Fixtures {
    fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun source(bytes: ByteArray): ZipSource = ZipSource { ByteArrayInputStream(bytes) }

    fun dm(id: String, vararg recipients: String) =
        """{"id": "$id", "type": 1, "recipients": [${recipients.joinToString { "\"$it\"" }}]}"""

    fun groupDm(id: String, vararg recipients: String) =
        """{"id": "$id", "type": 3, "recipients": [${recipients.joinToString { "\"$it\"" }}]}"""

    fun guildChannel(id: String) =
        """{"id": "$id", "type": 0, "name": "general", "guild": {"id": "900000000000000001", "name": "Some Server"}}"""

    fun jsonMessage(id: String, ts: String, contents: String, attachments: String = "") =
        """{"ID": $id, "Timestamp": "$ts", "Contents": ${quote(contents)}, "Attachments": ${quote(attachments)}}"""

    fun quote(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
        append('"')
    }

    /** Collects everything the importer hands over. */
    class RecordingSink(private val dedupe: Boolean = true) : ImportSink {
        val channels = ArrayList<ImportedChannel>()
        val messages = ArrayList<ImportedMessage>()
        val batchSizes = ArrayList<Int>()
        private val seen = HashSet<Long>()
        override fun onChannels(channels: List<ImportedChannel>) {
            this.channels.addAll(channels)
        }

        override fun onMessages(batch: List<ImportedMessage>): Int {
            batchSizes.add(batch.size)
            var n = 0
            for (m in batch) if (!dedupe || seen.add(m.messageId)) {
                messages.add(m)
                n++
            }
            return n
        }
    }
}
