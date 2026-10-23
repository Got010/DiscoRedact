package app.discoredact.core.signin

import app.discoredact.core.signin.SignInCapture.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SignInCaptureTest {
    private val token = "TOKEN-SENTINEL-abc.def.ghi"
    private val ua = "Mozilla/5.0 (Linux; Android 16; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    private val pageUa = "Mozilla/5.0 (PAGE-UA)"
    private val props = "PROPS-SENTINEL-eyJvcyI6IkFuZHJvaWQifQ=="
    private val api = "https://discord.com/api/v9/users/@me/affinities/users"

    private fun full(vararg extra: Pair<String, String>) = mapOf(
        "Authorization" to token,
        "User-Agent" to ua,
        "X-Super-Properties" to props,
        "X-Discord-Locale" to "en-US",
        "X-Discord-Timezone" to "America/Chicago",
        "Cookie" to "COOKIE-SENTINEL",
        "X-Debug-Options" to "bugReporterEnabled",
    ) + extra

    @Test fun allFivePresentGiveTheSet() {
        val h = SignInCapture.fromRequest(api, full(), pageUa, 42L)
        assertNotNull(h)
        h!!
        assertEquals(token, h.authorization); assertEquals(ua, h.userAgent); assertEquals(props, h.superProperties)
        assertEquals("en-US", h.locale); assertEquals("America/Chicago", h.timezone); assertEquals(42L, h.capturedAt)
    }

    @Test fun theRequestUserAgentWinsAndThePagesIsTheFallback() {
        assertEquals(ua, SignInCapture.fromRequest(api, full(), pageUa, 1L)!!.userAgent)
        assertEquals(pageUa, SignInCapture.fromRequest(api, full() - "User-Agent", pageUa, 1L)!!.userAgent)
        assertEquals(pageUa, SignInCapture.fromRequest(api, full("User-Agent" to " "), pageUa, 1L)!!.userAgent)
        val o = SignInCapture.inspect(api, full() - "User-Agent", "", 1L)
        assertEquals(Outcome.Unusable(listOf("user-agent"), emptyList()), o)
    }

    @Test fun missingAnyOneOfTheOthersGivesNull() {
        for (drop in listOf("X-Super-Properties", "X-Discord-Locale", "X-Discord-Timezone")) {
            assertNull(drop, SignInCapture.fromRequest(api, full() - drop, pageUa, 1L))
            val o = SignInCapture.inspect(api, full() - drop, pageUa, 1L)
            assertEquals(Outcome.Unusable(listOf(drop.lowercase()), emptyList()), o)
        }
        assertNull(SignInCapture.fromRequest(api, full("X-Discord-Locale" to "   "), pageUa, 1L))
    }

    @Test fun noAuthorizationIsNotSignedInOrHidden() {
        assertSame(Outcome.NotSignedIn, SignInCapture.inspect(api, full() - "Authorization", pageUa, 1L))
        assertSame(Outcome.NotSignedIn, SignInCapture.inspect(api, full("Authorization" to ""), pageUa, 1L))
        assertSame(Outcome.HeadersHidden, SignInCapture.inspect(api, mapOf("Accept" to "*/*", "User-Agent" to ua), pageUa, 1L))
        assertSame(Outcome.HeadersHidden, SignInCapture.inspect(api, emptyMap(), pageUa, 1L))
        assertNull(SignInCapture.fromRequest(api, emptyMap(), pageUa, 1L))
    }

    @Test fun aNonApiUrlOrAnotherHostGivesNull() {
        for (url in listOf(
            "https://discord.com/login",
            "https://discord.com/apiv9/users/@me",
            "https://discord.com/assets/api/x.js",
            "https://discord.com/API/v9/users/@me",
            "http://discord.com/api/v9/users/@me",
            "https://ptb.discord.com/api/v9/users/@me",
            "https://canary.discord.com/api/v9/users/@me",
            "https://discordapp.com/api/v9/users/@me",
            "https://cdn.discordapp.com/api/v9/users/@me",
            "https://hcaptcha.com/api/x",
            "https://discord.com.evil.example/api/v9/users/@me",
            "https://discord.com@evil.example/api/v9/users/@me",
            "https://evil.example/api/v9/?h=https://discord.com/api/",
            "https://discord.com:8443/api/v9/users/@me",
            "https://user@discord.com/api/v9/users/@me",
            "wss://discord.com/api/v9/",
            "",
        )) {
            assertNull(url, SignInCapture.fromRequest(url, full(), pageUa, 1L))
            assertSame(url, Outcome.NotApi, SignInCapture.inspect(url, full(), pageUa, 1L))
        }
        assertTrue(SignInCapture.isApi("https://discord.com/api/v9/auth/login"))
        assertTrue(SignInCapture.isApi("https://discord.com/api/v10/users/@me?with=1"))
    }

    @Test fun headerNamesMatchInAnyCase() {
        val mixed = mapOf(
            "authorization" to token, "USER-AGENT" to ua, "x-Super-PROPERTIES" to props,
            "X-DISCORD-LOCALE" to "en-GB", "x-discord-timezone" to "Europe/London",
        )
        val h = SignInCapture.fromRequest(api, mixed, pageUa, 7L)!!
        assertEquals(token, h.authorization); assertEquals(ua, h.userAgent); assertEquals("en-GB", h.locale)
        assertEquals("Europe/London", h.timezone)
    }

    @Test fun aNonAsciiValueGivesNullAndNamesTheFieldOnly() {
        val bad = "SECRETéVALUE"
        for (field in listOf("Authorization", "User-Agent", "X-Super-Properties", "X-Discord-Locale", "X-Discord-Timezone")) {
            val headers = full(field to bad)
            assertNull(field, SignInCapture.fromRequest(api, headers, pageUa, 1L))
            val o = SignInCapture.inspect(api, headers, pageUa, 1L)
            assertEquals(Outcome.Unusable(emptyList(), listOf(field.lowercase())), o)
            assertFalse(o.toString().contains("SECRET"))
        }
        assertNull(SignInCapture.fromRequest(api, full("X-Discord-Timezone" to "America/\nChicago"), pageUa, 1L))
        assertNull(SignInCapture.fromRequest(api, full("X-Super-Properties" to "a\u0000b"), pageUa, 1L))
    }

    @Test fun aNullNameOrValueInThePlatformsMapIsSkipped() {
        @Suppress("UNCHECKED_CAST")
        val withNulls = (HashMap<String?, String?>(full()).apply { put(null, "x"); put("X-Other", null) }) as Map<String, String>
        assertNotNull(SignInCapture.fromRequest(api, withNulls, pageUa, 1L))
        @Suppress("UNCHECKED_CAST")
        val nullAuth = (HashMap<String?, String?>(full()).apply { put("Authorization", null) }) as Map<String, String>
        assertSame(Outcome.NotSignedIn, SignInCapture.inspect(api, nullAuth, pageUa, 1L))
    }

    /** Secret hygiene (CLAUDE.md rule 9): no outcome, failure or printed text carries a header value. */
    @Test fun noOutcomeOrFailureCarriesAValue() {
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val texts = StringBuilder()
        System.setOut(PrintStream(out)); System.setErr(PrintStream(out))
        try {
            val cases = listOf(
                full(), full() - "X-Discord-Locale", full("User-Agent" to "UA-é-SENTINEL"),
                full("Authorization" to "TOKEN-SENTINEL- "), full() - "Authorization", emptyMap(),
            )
            for (headers in cases) for (url in listOf(api, "https://discord.com/login", "https://evil.example/api/")) {
                val o = SignInCapture.inspect(url, headers, pageUa, 1L)
                texts.append(o.toString()).append('\n')
                if (o is Outcome.Captured) texts.append(o.headers.toString()).append('\n')
                if (o is Outcome.Unusable) texts.append(o.missing).append(o.invalid).append('\n')
                texts.append(SignInHosts.blockedLabel(url)).append('\n')
            }
        } finally {
            System.setOut(oldOut); System.setErr(oldErr)
        }
        val everything = texts.toString() + out.toString()
        assertTrue(everything.contains("Captured(values hidden)"))
        for (s in listOf("SENTINEL", token, props, ua, "America/Chicago", "en-US")) assertFalse(s, everything.contains(s))
    }
}
