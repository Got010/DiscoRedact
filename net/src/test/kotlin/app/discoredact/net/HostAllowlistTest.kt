package app.discoredact.net

import app.discoredact.core.executor.DeleteRequest
import app.discoredact.core.executor.GetRequest
import app.discoredact.core.executor.TEST_HEADERS
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CLAUDE.md rule 2: no host but discord.com, and the client is shaped as the spec says. */
class HostAllowlistTest {
    private val allow = HostAllowlist()

    @Test fun onlyHttpsDiscordComPasses() {
        allow.check("https://discord.com/api/v9/channels/1/messages/2".toHttpUrl())
        allow.check("https://DISCORD.com/".toHttpUrl())
        for (bad in listOf(
            "https://cdn.discordapp.com/attachments/1/2/x.png",
            "https://discord.com.evil/api/v9/channels/1/messages/2",
            "https://evil-discord.com/",
            "https://api.discord.com/",
            "https://discordapp.com/api/v9",
            "http://discord.com/api/v9/channels/1/messages/2",
            "https://127.0.0.1/api/v9",
        )) {
            try {
                allow.check(bad.toHttpUrl()); fail("allowed $bad")
            } catch (e: HostNotAllowedException) {
                assertTrue(e.message!!.contains("not the allowed host"))
            }
        }
    }

    @Test fun theClientRefusesBeforeAnyConnection() {
        // A base on another host: refused by the interceptor, which runs before DNS; no socket is opened.
        for (base in listOf("https://cdn.discordapp.com/api/v9", "https://discord.com.evil/api/v9", "http://discord.com/api/v9")) {
            val t = DiscordTransport.forTests(base, HostAllowlist())
            try {
                t.send(DeleteRequest(1, 2), TEST_HEADERS); fail("sent to $base")
            } catch (_: HostNotAllowedException) {
            }
        }
    }

    @Test fun theProductionClientIsShapedAsTheSpecSays() {
        assertEquals("https://discord.com/api/v9", DiscordTransport.PRODUCTION_BASE_URL)
        assertEquals("discord.com", HostAllowlist.DISCORD_HOST)
        val client = DiscordTransport.buildClient(HostAllowlist())
        assertEquals(listOf(HostAllowlist::class.java), client.interceptors.map { it.javaClass })
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(1, client.dispatcher.maxRequests)
        assertEquals(1, client.dispatcher.maxRequestsPerHost)
        assertNull(client.cache)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(!client.followRedirects && !client.followSslRedirects && !client.retryOnConnectionFailure)
    }

    @Test fun onlyTheFourRequestsPassTheShapeCheck() {
        val base = "https://discord.com/api/v9"
        val allowed = listOf(
            "DELETE" to "$base/channels/10/messages/20",
            "GET" to "$base/users/@me",
            "GET" to "$base/users/@me/channels",
            "GET" to "$base/channels/10/messages?limit=100",
            "GET" to "$base/channels/10/messages?limit=100&before=99",
        )
        for ((m, u) in allowed) assertTrue("$m $u", RequestShape.allowed(m, u.toHttpUrl()))
        val refused = listOf(
            "GET" to "$base/users/@me/guilds",
            "GET" to "$base/users/@me/relationships",
            "GET" to "$base/users/123",
            "GET" to "$base/users/123/profile",
            "GET" to "$base/channels/10",
            "GET" to "$base/channels/10/messages",
            "GET" to "$base/channels/10/messages?limit=50",
            "GET" to "$base/channels/10/messages?limit=100&around=5",
            "GET" to "$base/channels/10/messages?limit=100&after=5",
            "GET" to "$base/channels/10/messages?before=5&limit=100",
            "GET" to "$base/channels/10/messages?limit=100&before=5&x=1",
            "GET" to "$base/channels/10/messages?limit=100&before=abc",
            "GET" to "$base/channels/10/messages/search?content=x",
            "GET" to "$base/channels/10/messages/20",
            "GET" to "$base/guilds/1/messages/search?author_id=2",
            "GET" to "$base/guilds/1/channels",
            "GET" to "$base/users/@me?with_analytics_token=true",
            "GET" to "$base/gateway",
            "GET" to "https://discord.com/api/v10/users/@me",
            "GET" to "https://discord.com/users/@me",
            "GET" to "$base/channels/abc/messages?limit=100",
            "GET" to "$base/users/@me#x",
            "POST" to "$base/users/@me/channels",
            "POST" to "$base/channels/10/messages/bulk-delete",
            "PATCH" to "$base/channels/10/messages/20",
            "PUT" to "$base/users/@me",
            "HEAD" to "$base/users/@me",
            "DELETE" to "$base/channels/10/messages",
            "DELETE" to "$base/channels/10/messages/20?x=1",
            "DELETE" to "$base/channels/10",
        )
        for ((m, u) in refused) {
            assertTrue("$m $u", !RequestShape.allowed(m, u.toHttpUrl()))
            try {
                RequestShape.check(m, u.toHttpUrl()); fail("allowed $m $u")
            } catch (e: RequestNotAllowedException) {
                assertTrue(e.message!!.contains("not one of the four"))
            }
        }
    }

    @Test fun theProductionReadsAreBuiltFromTheShapesOnly() {
        val t = DiscordTransport.production()
        assertEquals("https://discord.com/api/v9/users/@me", t.urlOf(GetRequest.Me).toString())
        assertEquals("https://discord.com/api/v9/users/@me/channels", t.urlOf(GetRequest.MyChannels).toString())
        assertEquals("https://discord.com/api/v9/channels/7/messages?limit=100", t.urlOf(GetRequest.Messages(7, null)).toString())
        assertEquals("https://discord.com/api/v9/channels/7/messages?limit=100&before=8", t.urlOf(GetRequest.Messages(7, 8)).toString())
        for (r in listOf(GetRequest.Me, GetRequest.MyChannels, GetRequest.Messages(7, null), GetRequest.Messages(7, 8))) {
            assertTrue(RequestShape.allowed("GET", t.urlOf(r)))
        }
    }

    @Test fun aReadToAnyOtherHostIsRefusedBeforeAnyConnection() {
        for (base in listOf("https://cdn.discordapp.com/api/v9", "https://discord.com.evil/api/v9", "http://discord.com/api/v9", "https://gateway.discord.gg/api/v9")) {
            val t = DiscordTransport.forTests(base, HostAllowlist())
            for (r in listOf(GetRequest.Me, GetRequest.MyChannels, GetRequest.Messages(1, 2))) {
                try {
                    t.get(r, TEST_HEADERS); fail("read from $base")
                } catch (_: HostNotAllowedException) {
                }
            }
        }
    }
}
