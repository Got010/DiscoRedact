package app.discoredact.core.history

import app.discoredact.core.executor.ApiResponse
import app.discoredact.core.importer.ImportedChannel
import app.discoredact.core.importer.ImportedMessage
import app.discoredact.core.model.ChannelState
import app.discoredact.core.model.RowSource

/** The fetch's store in memory, with the Room store's rules: a page's rows and progress land together. */
class FakeFetchStore : FetchStore {
    class Fetch(
        var startedAt: Long? = null, var endedAt: Long? = null, var stopReason: String? = null, var notBefore: Long? = null,
        var accountId: Long? = null, var capturedAt: Long? = null, var listedAt: Long? = null,
    )
    class Row(val message: ImportedMessage, var source: RowSource)
    class Chan(val channel: ImportedChannel, var source: RowSource, var state: ChannelState = ChannelState.PENDING, var error: String? = null)

    var fetch: Fetch? = Fetch()
    val progress = sortedMapOf<Long, FetchProgress>()
    val messages = LinkedHashMap<Long, Row>()
    val channels = LinkedHashMap<Long, Chan>()
    val events = ArrayList<FetchEventRecord>()
    val readsByDay = HashMap<Long, Int>()
    val deleteTimes = ArrayList<Long>()
    /** Every write, in order: "page <channel>", "channels", "outcome <channel>". */
    val writes = ArrayList<String>()
    /** Throws on the next [pageWritten] when set (a kill between the response and its rows). */
    var failNextPage = false

    @Synchronized override fun fetch(): FetchSnapshot? = fetch?.let { FetchSnapshot(it.startedAt, it.endedAt, it.stopReason, it.notBefore, it.accountId, it.capturedAt, it.listedAt) }
    @Synchronized override fun fetchStarted(now: Long) { val f = fetch!!; if (f.startedAt == null) f.startedAt = now; f.stopReason = null; f.notBefore = null }
    @Synchronized override fun fetchEnded(now: Long, reason: String) { val f = fetch!!; if (f.endedAt == null) { f.endedAt = now; f.stopReason = reason } }
    @Synchronized override fun fetchPaused(reason: String, resumeNotBefore: Long?) { val f = fetch!!; if (f.endedAt == null) { f.stopReason = reason; f.notBefore = resumeNotBefore } }
    @Synchronized override fun accountId(id: Long, capturedAt: Long) { fetch!!.accountId = id; fetch!!.capturedAt = capturedAt }
    @Synchronized override fun progress(channelId: Long): FetchProgress? = progress[channelId]
    @Synchronized override fun nextChannel(): FetchProgress? = progress.values.firstOrNull { !it.done }

    @Synchronized override fun channelsSeen(channels: List<ImportedChannel>, now: Long) {
        for (c in channels) {
            val existing = this.channels[c.channelId]
            if (existing == null) this.channels[c.channelId] = Chan(c, RowSource.API) else existing.source = existing.source.seenBy(RowSource.API)
            progress.putIfAbsent(c.channelId, FetchProgress(c.channelId, null, false))
        }
        fetch!!.listedAt = now
        writes.add("channels")
    }

    @Synchronized override fun upsertOwn(messages: List<ImportedMessage>, now: Long): Int {
        var inserted = 0
        for (m in messages) {
            val existing = this.messages[m.messageId]
            if (existing == null) { this.messages[m.messageId] = Row(m, RowSource.API); inserted++ } else existing.source = existing.source.seenBy(RowSource.API)
        }
        return inserted
    }

    @Synchronized override fun pageDone(channelId: Long, oldestId: Long?, done: Boolean, now: Long) {
        progress[channelId] = FetchProgress(channelId, oldestId, done)
    }

    @Synchronized override fun pageWritten(channelId: Long, own: List<ImportedMessage>, oldestId: Long?, done: Boolean, now: Long): Int {
        if (failNextPage) { failNextPage = false; throw IllegalStateException("killed") }
        writes.add("page $channelId")
        return super.pageWritten(channelId, own, oldestId, done, now)
    }

    @Synchronized override fun channelOutcome(channelId: Long, state: ChannelState, error: String, now: Long) {
        channels[channelId]?.let { it.state = state; it.error = error }
        progress[channelId] = (progress[channelId] ?: FetchProgress(channelId, null, false)).copy(done = true)
        writes.add("outcome $channelId")
    }

    @Synchronized override fun channelReadable(channelId: Long, now: Long) {
        val c = channels[channelId] ?: return
        if (c.state == ChannelState.INACCESSIBLE || c.state == ChannelState.GONE) { c.state = ChannelState.PENDING; c.error = null }
    }

    @Synchronized override fun event(event: FetchEventRecord) { events.add(event) }
    @Synchronized override fun readSent(now: Long, dayStartMs: Long) { readsByDay.merge(dayStartMs, 1, Int::plus) }
    @Synchronized override fun readsOn(dayStartMs: Long): Int = readsByDay[dayStartMs] ?: 0
    @Synchronized override fun deletedSince(sinceMs: Long): Int = deleteTimes.count { it >= sinceMs }
    @Synchronized override fun invalidResponseTimesSince(sinceMs: Long): List<Long> = events.filter {
        it.ts >= sinceMs && (it.httpStatus == 401 || it.httpStatus == 403 || (it.httpStatus == 429 && it.actionTaken != "RATE_LIMITED shared"))
    }.map { it.ts }

    fun actions(): List<String> = events.map { it.actionTaken }
    fun own(channelId: Long): List<Long> = messages.values.filter { it.message.channelId == channelId }.map { it.message.messageId }
}

/** Discord-shaped bodies for the three reads. Ids are decimal strings, as the API sends them. */
object Pages {
    const val ME = 900L
    const val OTHER = 901L

    fun me(id: Long? = ME) = ok(if (id == null) """{"username":"alt"}""" else """{"id":"$id","username":"alt","global_name":"Alt"}""")

    fun dm(id: Long, vararg recipients: Pair<Long, String>) =
        """{"id":"$id","type":1,"last_message_id":null,"recipients":[${recipients.joinToString(",") { (r, n) -> """{"id":"$r","username":"$n","global_name":null}""" }}]}"""

    fun group(id: Long, name: String?, vararg recipients: Long) =
        """{"id":"$id","type":3,"name":${name?.let { "\"$it\"" } ?: "null"},"recipients":[${recipients.joinToString(",") { """{"id":"$it","username":"u$it"}""" }}]}"""

    fun channels(vararg rows: String) = ok("[${rows.joinToString(",")}]")

    /** A message object; [timestamp] null leaves the field out. */
    fun msg(id: Long, author: Long, channelId: Long, content: String = "text $id", timestamp: String? = "2024-05-01T10:00:00.000000+00:00", attachments: Int = 0) =
        """{"id":"$id","type":0,"channel_id":"$channelId","author":{"id":"$author","username":"a$author"},"content":${quote(content)},""" +
            (if (timestamp != null) """"timestamp":"$timestamp",""" else "") +
            """"attachments":[${(1..attachments).joinToString(",") { """{"id":"$it","filename":"f$it.png"}""" }}],"embeds":[]}"""

    /** A page of [count] rows of [channelId], newest first, ids from [newest] down; every [ownEvery]-th row is the account's. */
    fun pageRows(channelId: Long, newest: Long, count: Int, ownEvery: Int = 2): List<String> =
        (0 until count).map { i -> msg(newest - i, if (i % ownEvery == 0) ME else OTHER, channelId) }

    fun page(rows: List<String>) = ok("[${rows.joinToString(",")}]")

    fun ok(body: String) = ApiResponse(200, mapOf("content-type" to "application/json"), body)

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
