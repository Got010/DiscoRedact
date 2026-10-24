package app.discoredact.core.history

import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.HaltReason
import app.discoredact.core.executor.Outcome
import app.discoredact.core.executor.RateLimitScope
import app.discoredact.core.executor.Responses
import org.junit.Assert.assertEquals
import org.junit.Test

/** The response table on a read (spec section 9 with the narrowing of B34 API history, section 4). */
class ReadClassifierTest {
    private val page = GetRequest.Messages(10, null)
    private fun failed(r: app.discoredact.core.executor.ApiResponse, req: GetRequest = page) = (ReadClassifier.classify(r, req) as ReadOutcome.Failed).outcome

    @Test fun twoHundredIsAPageWhateverItsBodySays() {
        assertEquals(ReadOutcome.Ok, ReadClassifier.classify(Pages.ok("""[{"content":"captcha_key"}]"""), page))
        assertEquals(ReadOutcome.Ok, ReadClassifier.classify(Pages.ok("<html></html>"), page))
    }

    @Test fun theTableOnADmRead() {
        assertEquals(Outcome.ChannelGone, failed(Responses.unknownChannel))
        assertEquals(Outcome.ChannelInaccessible(50001), failed(Responses.noAccess))
        assertEquals(Outcome.ChannelInaccessible(50013), failed(Responses.missingPermissions))
        assertEquals(Outcome.Halt(HaltReason.TOKEN_INVALID, 401, 0), failed(Responses.unauthorized))
        assertEquals(HaltReason.CAPTCHA, (failed(Responses.captcha) as Outcome.Halt).reason)
        assertEquals(HaltReason.EDGE_BLOCK, (failed(Responses.html) as Outcome.Halt).reason)
        assertEquals(Outcome.Transient(502), failed(Responses.serverError))
        assertEquals(Outcome.Transient(500), failed(Responses.emptyServerError))
        assertEquals(RateLimitScope.SHARED, (failed(Responses.rateLimited("shared", 1.5)) as Outcome.RateLimited).scope)
        assertEquals(1_500L, (failed(Responses.rateLimited("user", 1.5)) as Outcome.RateLimited).retryAfterMs)
    }

    @Test fun thePerMessageRowsMeanNothingForAReadAndHalt() {
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 404, 10008), failed(Responses.unknownMessage))
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 400, 50021), failed(Responses.systemMessage))
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 302, null), failed(Responses.json(302, "")))
    }

    @Test fun aChannelRowOnTheAccountReadsHalts() {
        for (req in listOf(GetRequest.Me, GetRequest.MyChannels)) {
            assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 404, 10003), failed(Responses.unknownChannel, req))
            assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 403, 50001), failed(Responses.noAccess, req))
        }
    }
}
