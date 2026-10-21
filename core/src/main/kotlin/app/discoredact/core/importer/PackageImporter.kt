package app.discoredact.core.importer

import app.discoredact.core.model.ChannelType
import app.discoredact.core.model.Snowflake
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

/**
 * Streams a Discord data package (spec section 4). Pass 1 reads only `messages/index.json` and each
 * `messages/<folder>/channel.json` (small files) to decide which folders are DMs or group DMs. Pass 2 streams each
 * accepted folder's `messages.json` / `messages.csv` and hands rows to the sink in batches, so memory stays flat for
 * packages with hundreds of thousands of messages. Malformed rows and files are counted, never fatal.
 */
class PackageImporter(private val limits: ImportLimits = ImportLimits()) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private class FolderInfo {
        var channelJson: JsonObject? = null
        var channelJsonBad = false
        var hasMessages = false
    }

    fun import(source: ZipSource, sink: ImportSink): ImportReport {
        var malformedFiles = 0
        val folders = LinkedHashMap<String, FolderInfo>()
        var index: Map<String, String?> = emptyMap()

        // Pass 1: index and channel metadata.
        source.open().use { raw ->
            val zip = ZipInputStream(raw.buffered())
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                when (val kind = PackagePaths.classify(entry.name)) {
                    PackageEntry.Index -> {
                        val parsed = readSmallJson(zip, limits.maxIndexBytes)
                        if (parsed is JsonObject) {
                            index = parsed.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }
                        } else {
                            malformedFiles++
                        }
                    }
                    is PackageEntry.ChannelJson -> {
                        val info = folders.getOrPut(kind.folder) { FolderInfo() }
                        val parsed = readSmallJson(zip, limits.maxChannelJsonBytes)
                        if (parsed is JsonObject) info.channelJson = parsed else {
                            info.channelJsonBad = true
                            malformedFiles++
                        }
                    }
                    is PackageEntry.MessagesJson, is PackageEntry.MessagesCsv -> {
                        val folder = if (kind is PackageEntry.MessagesJson) kind.folder else (kind as PackageEntry.MessagesCsv).folder
                        folders.getOrPut(folder) { FolderInfo() }.hasMessages = true
                    }
                    PackageEntry.Ignored -> Unit
                }
            }
        }

        // Decide which folders are DM / group-DM channels.
        var skippedGuild = 0
        var skippedOther = 0
        val accepted = LinkedHashMap<String, ImportedChannel>()
        val byId = LinkedHashMap<Long, ImportedChannel>()
        for ((folder, info) in folders) {
            val cj = info.channelJson
            if (cj == null) {
                skippedOther++
                continue
            }
            if (cj.keys.any { it.equals("guild", ignoreCase = true) && cj[it] !is JsonNull } ||
                cj.keys.any { it.equals("guild_id", ignoreCase = true) && cj[it] !is JsonNull }
            ) {
                skippedGuild++
                continue
            }
            val type = channelType(cj["type"])
            if (type == null) {
                skippedOther++
                continue
            }
            val id = Snowflake.parse(primitiveText(cj["id"])) ?: PackagePaths.folderDigits(folder)
            if (id == null) {
                skippedOther++
                continue
            }
            val recipients = (cj["recipients"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
                .orEmpty()
            val name = index[id.toString()]?.takeIf { it.isNotBlank() }
                ?: if (type == ChannelType.DM) "Direct message $id" else "Group DM $id"
            val channel = byId.getOrPut(id) { ImportedChannel(id, type, name, recipients) }
            accepted[folder] = channel
        }
        val channels = byId.values.toList()
        sink.onChannels(channels)

        // Pass 2: messages of accepted folders only.
        val perChannel = LinkedHashMap<Long, Int>()
        channels.forEach { perChannel[it.channelId] = 0 }
        var malformedRows = 0L
        var inserted = 0L
        var fromId = 0L
        var read = 0L
        val doneFolders = HashSet<String>()
        val batch = ArrayList<ImportedMessage>(limits.batchSize)

        fun flush() {
            if (batch.isNotEmpty()) {
                inserted += sink.onMessages(ArrayList(batch))
                batch.clear()
            }
        }

        fun accept(channelId: Long, idText: String?, ts: String?, content: String?, attachments: Boolean) {
            val id = Snowflake.parse(idText)
            if (id == null) {
                malformedRows++
                return
            }
            val parsed = Timestamps.parseMillis(ts)
            val millis = parsed ?: Snowflake.timestampMillis(id).also { fromId++ }
            batch.add(ImportedMessage(id, channelId, millis, content.orEmpty(), attachments))
            perChannel[channelId] = (perChannel[channelId] ?: 0) + 1
            read++
            if (batch.size >= limits.batchSize) {
                flush()
                sink.onProgress(ImportProgress(doneFolders.size, accepted.size, read))
            }
        }

        source.open().use { raw ->
            val zip = ZipInputStream(raw.buffered())
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val kind = PackagePaths.classify(entry.name)
                val folder = when (kind) {
                    is PackageEntry.MessagesJson -> kind.folder
                    is PackageEntry.MessagesCsv -> kind.folder
                    else -> null
                } ?: continue
                val channel = accepted[folder] ?: continue
                val ok = if (kind is PackageEntry.MessagesJson) {
                    readMessagesJson(zip) { id, ts, content, att -> accept(channel.channelId, id, ts, content, att) }
                        .also { malformedRows += it.malformedRows }.ok
                } else {
                    readMessagesCsv(zip) { id, ts, content, att -> accept(channel.channelId, id, ts, content, att) }
                        .also { malformedRows += it.malformedRows }.ok
                }
                if (!ok) malformedFiles++
                doneFolders.add(folder)
                flush()
                sink.onProgress(ImportProgress(doneFolders.size, accepted.size, read))
            }
        }
        flush()

        return ImportReport(
            channels = channels.size,
            dmChannels = channels.count { it.type == ChannelType.DM },
            groupDmChannels = channels.count { it.type == ChannelType.GROUP_DM },
            skippedGuildChannels = skippedGuild,
            skippedOtherFolders = skippedOther,
            messagesPerChannel = perChannel,
            messagesInserted = inserted,
            malformedRows = malformedRows,
            malformedFiles = malformedFiles,
            timestampsFromId = fromId,
        )
    }

    private data class FileResult(val ok: Boolean, val malformedRows: Long)

    private fun interface RowHandler {
        fun row(id: String?, timestamp: String?, content: String?, hasAttachments: Boolean)
    }

    /**
     * Streams one messages.json. Only the decoder's own exceptions (bad JSON) mark the file malformed; an exception
     * from the sink (a database write) or the stream (a broken ZIP) propagates and aborts the import.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun readMessagesJson(zip: ZipInputStream, handler: RowHandler): FileResult {
        var bad = 0L
        val iterator = try {
            json.decodeToSequence<JsonElement>(withoutBom(NonClosing(zip)), DecodeSequenceMode.ARRAY_WRAPPED).iterator()
        } catch (_: IllegalArgumentException) {
            return FileResult(false, bad) // SerializationException is an IllegalArgumentException
        }
        while (true) {
            val el = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (_: IllegalArgumentException) {
                return FileResult(false, bad) // bad JSON: rows read so far stay, the rest of this file is skipped
            }
            val obj = el as? JsonObject
            if (obj == null) {
                bad++
                continue
            }
            val id = primitiveText(field(obj, "ID"))
            val ts = primitiveText(field(obj, "Timestamp"))
            val contentEl = field(obj, "Contents")
            val content = (contentEl as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            if (content != null && content.length > limits.maxFieldChars) {
                bad++
                continue
            }
            if (id == null || Snowflake.parse(id) == null) {
                bad++
                continue
            }
            handler.row(id, ts, content, hasAttachments(field(obj, "Attachments")))
        }
        return FileResult(true, bad)
    }

    /** Skips a UTF-8 byte-order mark at the start of a stream. */
    private fun withoutBom(input: InputStream): InputStream {
        val p = PushbackInputStream(input, 3)
        val head = ByteArray(3)
        var n = 0
        while (n < 3) {
            val r = p.read(head, n, 3 - n)
            if (r < 0) break
            n += r
        }
        val bom = n == 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
        if (!bom && n > 0) p.unread(head, 0, n)
        return p
    }

    private fun readMessagesCsv(zip: ZipInputStream, handler: RowHandler): FileResult {
        var bad = 0L
        val csv = CsvReader(InputStreamReader(NonClosing(zip), Charsets.UTF_8).buffered(), limits.maxFieldChars)
        val header = when (val h = csv.next()) {
            is CsvReader.Record.Row -> h.fields.map { it.trim().lowercase() }
            else -> return FileResult(false, 0)
        }
        val iId = header.indexOf("id")
        val iTs = header.indexOf("timestamp")
        val iContent = header.indexOf("contents")
        val iAtt = header.indexOf("attachments")
        if (iId < 0) return FileResult(false, 0)
        while (true) {
            when (val r = csv.next() ?: break) {
                CsvReader.Record.Malformed -> bad++
                is CsvReader.Record.Row -> {
                    if (r.fields.size == 1 && r.fields[0].isEmpty()) continue // blank line
                    if (r.fields.size != header.size) {
                        bad++
                        continue
                    }
                    val id = r.fields[iId]
                    if (Snowflake.parse(id) == null) {
                        bad++
                        continue
                    }
                    handler.row(
                        id,
                        if (iTs >= 0) r.fields[iTs] else null,
                        if (iContent >= 0) r.fields[iContent] else null,
                        iAtt >= 0 && r.fields[iAtt].isNotBlank(),
                    )
                }
            }
        }
        return FileResult(true, bad)
    }

    private fun field(obj: JsonObject, name: String): JsonElement? =
        obj[name] ?: obj.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun hasAttachments(el: JsonElement?): Boolean = when (el) {
        null, JsonNull -> false
        is JsonPrimitive -> el.content.isNotBlank()
        is JsonArray -> el.isNotEmpty()
        is JsonObject -> el.isNotEmpty()
    }

    private fun primitiveText(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun channelType(el: JsonElement?): ChannelType? {
        val p = el as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return if (p.isString) {
            when (p.content.trim().uppercase()) {
                "DM", "1" -> ChannelType.DM
                "GROUP_DM", "3" -> ChannelType.GROUP_DM
                else -> null
            }
        } else {
            when (p.content.toIntOrNull()) {
                1 -> ChannelType.DM
                3 -> ChannelType.GROUP_DM
                else -> null
            }
        }
    }

    private fun readSmallJson(zip: ZipInputStream, max: Long): JsonElement? {
        val bytes = try {
            readCapped(zip, max)
        } catch (_: TooLarge) {
            return null
        }
        return try {
            json.parseToJsonElement(String(bytes, Charsets.UTF_8).removePrefix("﻿"))
        } catch (_: Exception) {
            null
        }
    }

    private class TooLarge : Exception()

    private fun readCapped(input: InputStream, max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw TooLarge()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Lets a reader consume one ZIP entry without closing the ZIP stream. */
    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
