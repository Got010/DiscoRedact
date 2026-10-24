package app.discoredact.core.history

import app.discoredact.core.executor.ApiResponse
import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.Outcome
import app.discoredact.core.executor.ResponseClassifier

/** What one read's response means. A 2xx is a page (or the id, or the list); everything else is the delete table's. */
sealed interface ReadOutcome {
    /** 2xx: the body is the answer, read by the parser of the request's shape. */
    data object Ok : ReadOutcome

    /** Not 2xx: the response table of spec section 9, as [ResponseClassifier] reads it, narrowed to reads. */
    data class Failed(val outcome: Outcome) : ReadOutcome
}

/**
 * The response table for reads (B34 API history, section 4): the delete table, with three narrowings. A 2xx is [ReadOutcome.Ok]
 * before anything looks at the body (a page holds message text, which may contain any word, `captcha_key` included).
 * 404 10003 and 403 50001/50013 mark the DM when the read was a DM's history and halt otherwise. The two per-message
 * rows (404 10008, 400 50021) mean nothing for a read and halt as an unexpected 4xx. Fail closed: anything unnamed halts.
 */
object ReadClassifier {
    fun classify(r: ApiResponse, request: GetRequest): ReadOutcome {
        if (r.status in 200..299) return ReadOutcome.Ok
        val o = ResponseClassifier.classify(r)
        val unknown = { Outcome.Halt(HaltReason.UNKNOWN_4XX, r.status, ResponseClassifier.discordCode(r)) }
        return ReadOutcome.Failed(
            when (o) {
                Outcome.Deleted, Outcome.AlreadyGone, Outcome.SystemMessage -> unknown()
                Outcome.ChannelGone, is Outcome.ChannelInaccessible -> if (request is GetRequest.Messages) o else unknown()
                is Outcome.RateLimited, is Outcome.Halt, is Outcome.Transient -> o
            },
        )
    }
}
