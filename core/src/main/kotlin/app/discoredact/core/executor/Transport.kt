package app.discoredact.core.executor

/** The one delete the app makes: `DELETE /channels/{channel_id}/messages/{message_id}` (CLAUDE.md rule 1). */
data class DeleteRequest(val channelId: Long, val messageId: Long)

/**
 * The three reads of the Fetch import (CLAUDE.md rule 1, amended for 0.3.0), and nothing else: each shape is built
 * from ids only, never from a string, so no other path can be asked for. The transport turns a shape into its path
 * under `https://discord.com/api/v9` ([pathSegments], [query]); `limit` is always [PAGE_LIMIT].
 */
sealed interface GetRequest {
    /** `GET /users/@me`: the account's own id. */
    data object Me : GetRequest

    /** `GET /users/@me/channels`: the open DMs and group DMs. */
    data object MyChannels : GetRequest

    /** `GET /channels/{channelId}/messages?limit=100[&before={before}]`: one page of a DM's history, newest first. */
    data class Messages(val channelId: Long, val before: Long?) : GetRequest {
        init {
            require(channelId > 0) { "channel id must be a positive snowflake" }
            require(before == null || before > 0) { "before must be a positive snowflake" }
        }
    }

    companion object {
        /** The page size of a history read; a page with fewer rows is the DM's last. */
        const val PAGE_LIMIT = 100

        /** The path below the API base, segment by segment. */
        fun pathSegments(r: GetRequest): List<String> = when (r) {
            Me -> listOf("users", "@me")
            MyChannels -> listOf("users", "@me", "channels")
            is Messages -> listOf("channels", r.channelId.toString(), "messages")
        }

        /** The query parameters in order; empty for the two account reads. */
        fun query(r: GetRequest): List<Pair<String, String>> = when (r) {
            Me, MyChannels -> emptyList()
            is Messages -> listOfNotNull("limit" to PAGE_LIMIT.toString(), r.before?.let { "before" to it.toString() })
        }
    }
}

/**
 * A response as the classifier sees it. Header names are lower-case. [body] is the raw text (empty for 204). Nothing
 * here is ever logged whole; only the status, the Discord code and the rate-limit headers reach the event log.
 */
data class ApiResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** Status and header names only: a page's body holds other people's messages and is never printed. */
    override fun toString(): String = "ApiResponse(status=$status, headers=${headers.keys.sorted()}, body=${body.length} chars)"
}

/** A request that got no response: a connection, DNS or TLS failure, a timeout. The executor treats it as transient. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Marks the transport's own refusals: a request to another host (CLAUDE.md rule 2) or outside the four of rule 1,
 * refused before any connection, so nothing was sent. The run and the fetch halt on one with
 * [HaltReason.REFUSED_LOCALLY] instead of letting the worker die on an uncaught exception.
 */
interface RefusedBeforeSending

/**
 * Sends one delete or one read. The real one (`:net`) uses OkHttp, one request in flight across both; the dry run uses
 * [DryRunTransport] and never opens a socket.
 */
interface Transport {
    @Throws(TransportException::class)
    fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse

    @Throws(TransportException::class)
    fun get(request: GetRequest, headers: HeaderSet): ApiResponse
}

/** Answers every request without any network: 204 for a delete, an empty page or object for a read. */
object DryRunTransport : Transport {
    override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse = ApiResponse(204, emptyMap(), "")

    override fun get(request: GetRequest, headers: HeaderSet): ApiResponse = when (request) {
        GetRequest.Me -> ApiResponse(200, emptyMap(), "{}")
        GetRequest.MyChannels, is GetRequest.Messages -> ApiResponse(200, emptyMap(), "[]")
    }
}
