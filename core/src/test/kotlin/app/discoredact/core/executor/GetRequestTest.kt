package app.discoredact.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** CLAUDE.md rule 1 as amended: three read shapes, built from ids only, and their exact paths. */
class GetRequestTest {
    /** Exhaustive over the sealed shapes: a fourth shape does not compile until this table names its path. */
    private fun path(r: GetRequest): String = when (r) {
        GetRequest.Me -> "/users/@me"
        GetRequest.MyChannels -> "/users/@me/channels"
        is GetRequest.Messages -> "/channels/${r.channelId}/messages"
    }

    private fun built(r: GetRequest): String {
        val q = GetRequest.query(r)
        return "/" + GetRequest.pathSegments(r).joinToString("/") + if (q.isEmpty()) "" else "?" + q.joinToString("&") { "${it.first}=${it.second}" }
    }

    @Test fun exactlyThreePathsAndLimitIsAlwaysOneHundred() {
        val table = mapOf(
            GetRequest.Me to "/users/@me",
            GetRequest.MyChannels to "/users/@me/channels",
            GetRequest.Messages(123, null) to "/channels/123/messages?limit=100",
            GetRequest.Messages(123, 456) to "/channels/123/messages?limit=100&before=456",
        )
        for ((r, want) in table) {
            assertEquals(want, built(r))
            assertEquals(path(r), want.substringBefore('?'))
        }
        assertEquals(100, GetRequest.PAGE_LIMIT)
        val permitted = GetRequest::class.java.permittedSubclasses
        if (permitted != null) assertEquals(3, permitted.size)
    }

    @Test fun idsMustBePositive() {
        for (bad in listOf({ GetRequest.Messages(0, null) }, { GetRequest.Messages(-1, null) }, { GetRequest.Messages(1, 0) }, { GetRequest.Messages(1, -9) })) {
            try {
                bad(); fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun theDryTransportAnswersEveryShapeWithoutANetwork() {
        assertEquals("{}", DryRunTransport.get(GetRequest.Me, TEST_HEADERS).body)
        assertEquals("[]", DryRunTransport.get(GetRequest.MyChannels, TEST_HEADERS).body)
        assertEquals(200, DryRunTransport.get(GetRequest.Messages(1, null), TEST_HEADERS).status)
    }

    @Test fun aResponsePrintsNoBody() {
        val r = ApiResponse(200, mapOf("x-ratelimit-bucket" to "b"), """[{"content":"SECRET"}]""")
        assertEquals(false, r.toString().contains("SECRET"))
    }
}
