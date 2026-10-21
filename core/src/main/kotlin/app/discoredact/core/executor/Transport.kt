package app.discoredact.core.executor

/** The one request the app makes: `DELETE /channels/{channel_id}/messages/{message_id}` (CLAUDE.md rule 1). */
data class DeleteRequest(val channelId: Long, val messageId: Long)

/**
 * A response as the classifier sees it. Header names are lower-case. [body] is the raw text (empty for 204). Nothing
 * here is ever logged whole; only the status, the Discord code and the rate-limit headers reach the event log.
 */
data class ApiResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** A request that got no response: a connection, DNS or TLS failure, a timeout. The executor treats it as transient. */
class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Sends one delete. The real one (`:net`) uses OkHttp; the dry run uses [DryRunTransport] and never opens a socket. */
interface Transport {
    @Throws(TransportException::class)
    fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse
}

/** Answers every request with 204 without any network. A dry run uses it; no header set is needed. */
object DryRunTransport : Transport {
    override fun send(request: DeleteRequest, headers: HeaderSet): ApiResponse = ApiResponse(204, emptyMap(), "")
}
