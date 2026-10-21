package app.discoredact.core.importer

import app.discoredact.core.model.ChannelType
import java.io.InputStream

/** Opens the picked ZIP from the start. The importer opens it twice (channels first, then messages). */
fun interface ZipSource {
    fun open(): InputStream
}

data class ImportedChannel(
    val channelId: Long,
    val type: ChannelType,
    val displayName: String,
    val recipientIds: List<String>,
)

data class ImportedMessage(
    val messageId: Long,
    val channelId: Long,
    val timestampMillis: Long,
    val content: String,
    val hasAttachments: Boolean,
)

/** Receives the import as it streams. [onMessages] returns how many rows were new (insert-or-ignore). */
interface ImportSink {
    fun onChannels(channels: List<ImportedChannel>)
    fun onMessages(batch: List<ImportedMessage>): Int
    fun onProgress(progress: ImportProgress) {}
}

data class ImportProgress(val channelsDone: Int, val channelsTotal: Int, val messagesRead: Long)

data class ImportReport(
    /** DM and group-DM channels accepted. */
    val channels: Int,
    val dmChannels: Int,
    val groupDmChannels: Int,
    /** Folders skipped because `channel.json` names a guild (server channels). */
    val skippedGuildChannels: Int,
    /** Folders skipped because their type is neither DM nor group DM, they lack `channel.json`, or have no ID. */
    val skippedOtherFolders: Int,
    /** Valid message rows read from the files, per channel ID. */
    val messagesPerChannel: Map<Long, Int>,
    /** Rows that were new to the database. */
    val messagesInserted: Long,
    /** Rows skipped because they could not be read (no or bad ID, wrong field count, over-long field). */
    val malformedRows: Long,
    /** Message files (or `index.json`/`channel.json`) that stopped parsing part-way (bad JSON, bad CSV header). */
    val malformedFiles: Int,
    /** Rows whose timestamp could not be parsed; their time comes from the message ID instead. */
    val timestampsFromId: Long,
) {
    val messagesRead: Long get() = messagesPerChannel.values.sumOf { it.toLong() }
    val duplicates: Long get() = messagesRead - messagesInserted
}

/** Size caps that keep a corrupt or hostile package from exhausting memory. */
data class ImportLimits(
    val maxIndexBytes: Long = 32L * 1024 * 1024,
    val maxChannelJsonBytes: Long = 1L * 1024 * 1024,
    val maxFieldChars: Int = 100_000,
    val batchSize: Int = 1_000,
)
