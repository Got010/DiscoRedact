package app.discoredact.core.importer

/**
 * Classifies ZIP entry names of a Discord data package. Only entries directly under a top-level `messages/`
 * folder (any case) are ever opened: `messages/index.json` and `messages/<folder>/{channel.json, messages.json,
 * messages.csv}`. Everything else (`account/`, `activity/`, `servers/`, `ads/`, support tickets, nested or
 * odd paths) is [PackageEntry.Ignored] and its bytes are never read by the importer (CLAUDE.md rule 4).
 */
sealed interface PackageEntry {
    data object Index : PackageEntry
    data class ChannelJson(val folder: String) : PackageEntry
    data class MessagesJson(val folder: String) : PackageEntry
    data class MessagesCsv(val folder: String) : PackageEntry
    data object Ignored : PackageEntry
}

object PackagePaths {
    private val DIGITS = Regex("[0-9]+")

    fun classify(rawName: String): PackageEntry {
        val parts = rawName.replace('\\', '/').split('/')
        if (!parts[0].equals("messages", ignoreCase = true)) return PackageEntry.Ignored
        if (parts.any { it == "." || it == ".." }) return PackageEntry.Ignored
        return when (parts.size) {
            2 -> if (parts[1].equals("index.json", ignoreCase = true)) PackageEntry.Index else PackageEntry.Ignored
            3 -> {
                val folder = parts[1]
                if (folder.isEmpty()) return PackageEntry.Ignored
                when (parts[2].lowercase()) {
                    "channel.json" -> PackageEntry.ChannelJson(folder)
                    "messages.json" -> PackageEntry.MessagesJson(folder)
                    "messages.csv" -> PackageEntry.MessagesCsv(folder)
                    else -> PackageEntry.Ignored
                }
            }
            else -> PackageEntry.Ignored
        }
    }

    /** The channel ID in a folder name such as `c123456` or `123456`: the longest run of digits. */
    fun folderDigits(folder: String): Long? =
        DIGITS.findAll(folder).map { it.value }.maxByOrNull { it.length }?.let { app.discoredact.core.model.Snowflake.parse(it) }
}
