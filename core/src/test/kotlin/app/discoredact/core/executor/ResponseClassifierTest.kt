package app.discoredact.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of spec section 9, plus the rate-limit header parsing. */
class ResponseClassifierTest {
    private fun c(r: ApiResponse) = ResponseClassifier.classify(r)

    @Test fun deleted() = assertEquals(Outcome.Deleted, c(Responses.ok))
    @Test fun unknownMessageIsAlreadyGone() = assertEquals(Outcome.AlreadyGone, c(Responses.unknownMessage))
    @Test fun unknownChannelIsChannelGone() = assertEquals(Outcome.ChannelGone, c(Responses.unknownChannel))
    @Test fun noAccessIsInaccessible() {
        assertEquals(Outcome.ChannelInaccessible(50001), c(Responses.noAccess))
        assertEquals(Outcome.ChannelInaccessible(50013), c(Responses.missingPermissions))
    }
    @Test fun systemMessageIsSkipped() = assertEquals(Outcome.SystemMessage, c(Responses.systemMessage))
    @Test fun unauthorizedHalts() = assertEquals(Outcome.Halt(HaltReason.TOKEN_INVALID, 401, 0), c(Responses.unauthorized))
    @Test fun captchaHaltsWhateverTheStatus() {
        assertEquals(HaltReason.CAPTCHA, (c(Responses.captcha) as Outcome.Halt).reason)
        assertEquals(HaltReason.CAPTCHA, (c(Responses.json(403, """{"captcha_key":["x"]}""")) as Outcome.Halt).reason)
    }
    @Test fun htmlBodyIsAnEdgeBlock() {
        assertEquals(Outcome.Halt(HaltReason.EDGE_BLOCK, 503, null), c(Responses.html))
        assertEquals(Outcome.Halt(HaltReason.EDGE_BLOCK, 403, null), c(Responses.json(403, "error code: 1020")))
    }
    @Test fun fiveHundredsAreTransient() {
        assertEquals(Outcome.Transient(502), c(Responses.serverError))
        assertEquals(Outcome.Transient(500), c(Responses.emptyServerError))
    }
    @Test fun unknownFourHundredsHaltWithStatusAndCode() {
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 403, 40002), c(Responses.json(403, """{"code": 40002, "message": "verify"}""")))
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 404, 0), c(Responses.json(404, """{"code": 0, "message": "404: Not Found"}""")))
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 400, 50035), c(Responses.json(400, """{"code": 50035}""")))
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 405, null), c(ApiResponse(405, emptyMap(), "")))
        assertEquals(Outcome.Halt(HaltReason.UNKNOWN_4XX, 418, null), c(Responses.json(418, "{}")))
    }
    @Test fun rateLimitScopesAndRetryAfter() {
        val shared = c(Responses.rateLimited("shared", 2.5)) as Outcome.RateLimited
        assertEquals(RateLimitScope.SHARED, shared.scope)
        assertEquals(2500L, shared.retryAfterMs)
        assertFalse(shared.countsAsInvalid)
        val user = c(Responses.rateLimited("user", 0.73)) as Outcome.RateLimited
        assertEquals(RateLimitScope.USER, user.scope)
        assertEquals(730L, user.retryAfterMs)
        assertTrue(user.countsAsInvalid)
        val global = c(Responses.rateLimited("user", 1.0, global = true)) as Outcome.RateLimited
        assertEquals(RateLimitScope.GLOBAL, global.scope)
        // No scope header and no body: user scope, Retry-After header in seconds.
        val bare = c(Responses.json(429, "", "retry-after" to "3")) as Outcome.RateLimited
        assertEquals(RateLimitScope.USER, bare.scope)
        assertEquals(3000L, bare.retryAfterMs)
        // Nothing at all: the default, never zero.
        val nothing = c(ApiResponse(429, emptyMap(), "")) as Outcome.RateLimited
        assertEquals(5000L, nothing.retryAfterMs)
    }
    @Test fun rateLimitHeadersAreParsedAndMissingOnesAreNull() {
        val info = RateLimitInfo.from(Responses.okWithLimit(1, 1.234, "bkt"))
        assertEquals(1, info.remaining)
        assertEquals(1.234, info.resetAfterSeconds!!, 1e-9)
        assertEquals("bkt", info.bucket)
        assertNull(info.scope)
        val none = RateLimitInfo.from(Responses.ok)
        assertNull(none.remaining); assertNull(none.resetAfterSeconds); assertNull(none.bucket)
        assertNull(RateLimitInfo.from(Responses.json(204, "", "x-ratelimit-reset-after" to "-1")).resetAfterSeconds)
        assertEquals(RateLimitScope.SHARED, RateLimitInfo.from(Responses.json(429, "", "X-RateLimit-Scope" to "Shared")).scope)
    }
    @Test fun discordCodeComesOnlyFromAJsonObject() {
        assertEquals(10008, ResponseClassifier.discordCode(Responses.unknownMessage))
        assertNull(ResponseClassifier.discordCode(Responses.html))
        assertNull(ResponseClassifier.discordCode(Responses.json(400, "[1,2]")))
    }

    @Test fun aHeaderSetAdmitsPrintableAsciiOnlyAndNeverEchoesAValue() {
        val secret = "SECRET\u00e9VALUE"
        for (field in 0..4) {
            val v = List(5) { "ok-value" }.toMutableList().also { it[field] = secret }
            try {
                HeaderSet(v[0], v[1], v[2], v[3], v[4], 0L); throw AssertionError("accepted field $field")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message!!.contains("SECRET"))
                assertTrue(e.message!!.contains("printable ASCII"))
            }
        }
        try {
            HeaderSet("t", "ua", "props", " ", "tz", 0L); throw AssertionError()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blank"))
        }
        assertFalse(HeaderSet("TOKEN-X", "ua", "props", "en-US", "UTC", 1L).toString().contains("TOKEN-X"))
    }
}
