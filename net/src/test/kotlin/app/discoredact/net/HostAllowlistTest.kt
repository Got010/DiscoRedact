package app.discoredact.net

import app.discoredact.core.executor.DeleteRequest
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
}
