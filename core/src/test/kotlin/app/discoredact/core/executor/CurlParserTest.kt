package app.discoredact.core.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlParserTest {
    private val token = "TOKEN-SENTINEL-abc.def.ghi"
    private val ua = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    private val props = "eyJvcyI6IkxpbnV4IiwiYnJvd3NlciI6IkNocm9tZSJ9"

    private val chromeBash = """
        curl 'https://discord.com/api/v9/channels/1/messages/2' \
          -X 'DELETE' \
          -H 'accept: */*' \
          -H 'accept-language: en-US,en;q=0.9' \
          -H 'authorization: $token' \
          -b 'cookie1=secret-cookie; cookie2=x' \
          -H 'origin: https://discord.com' \
          -H 'referer: https://discord.com/channels/@me/1' \
          -H 'user-agent: $ua' \
          -H 'x-discord-locale: en-US' \
          -H 'x-discord-timezone: America/Chicago' \
          -H 'x-super-properties: $props' \
          --compressed
    """.trimIndent()

    private val chromeCmd = "curl \"https://discord.com/api/v9/channels/1/messages/2\" ^\r\n  -X \"DELETE\" ^\r\n  -H \"authorization: $token\" ^\r\n" +
        "  -H \"user-agent: $ua\" ^\r\n  -H \"x-super-properties: $props\" ^\r\n  -H \"x-discord-locale: en-US\" ^\r\n  -H \"x-discord-timezone: America/Chicago\" ^\r\n  --compressed"

    @Test fun chromeBashShapeYieldsTheFiveHeadersAndNothingElse() {
        val p = CurlParser.parse(chromeBash)
        assertTrue(p.complete)
        assertEquals(setOf("authorization", "user-agent", "x-super-properties", "x-discord-locale", "x-discord-timezone"), p.found.keys)
        val h = p.toHeaderSet(5L)!!
        assertEquals(token, h.authorization); assertEquals(ua, h.userAgent); assertEquals(props, h.superProperties)
        assertEquals("en-US", h.locale); assertEquals("America/Chicago", h.timezone); assertEquals(5L, h.capturedAt)
        assertFalse(p.toString().contains("SENTINEL")) // names only
    }

    @Test fun chromeCmdShapeWithCaretContinuations() {
        val p = CurlParser.parse(chromeCmd)
        assertTrue("missing ${p.missing}", p.complete)
        assertEquals(token, p.found["authorization"])
    }

    @Test fun firefoxShapeAndMixedCaseNames() {
        val text = "curl 'https://discord.com/api/v9/users/@me' -H 'User-Agent: $ua' -H 'Authorization: $token' -H 'X-Super-Properties: $props' -H 'X-Discord-Locale: en-GB' -H 'X-Discord-Timezone: Europe/London'"
        val p = CurlParser.parse(text)
        assertTrue(p.complete)
        assertEquals("en-GB", p.found["x-discord-locale"])
    }

    @Test fun ansiCQuotesAndHeaderEqualsForm() {
        val text = "curl 'https://discord.com/api/v9/x' -H \$'authorization: $token' --header='user-agent: $ua' -H\"x-super-properties: $props\" -H 'x-discord-locale: en-US' -H 'x-discord-timezone: UTC'"
        val p = CurlParser.parse(text)
        assertTrue("missing ${p.missing}", p.complete)
        assertEquals(ua, p.found["user-agent"])
        assertEquals(props, p.found["x-super-properties"])
    }

    @Test fun aBarePasteOfTheHeadersPanelWorks() {
        val text = "Authorization: $token\nUser-Agent: $ua\nX-Super-Properties: $props\nX-Discord-Locale: en-US\nX-Discord-Timezone: UTC\nCookie: secret\n"
        val p = CurlParser.parse(text)
        assertTrue(p.complete)
        assertNull(p.found["cookie"])
    }

    @Test fun missingFieldsAreNamedAndNoValueAppears() {
        val p = CurlParser.parse("curl 'https://discord.com/api/v9/x' -H 'authorization: $token' -H 'user-agent: $ua'")
        assertFalse(p.complete)
        assertEquals(listOf("x-super-properties", "x-discord-locale", "x-discord-timezone"), p.missing)
        assertNull(p.toHeaderSet(1L))
        assertFalse(p.toString().contains("SENTINEL"))
    }

    @Test fun garbageYieldsNothingAndNoCrash() {
        for (text in listOf("", "   ", "Invoke-WebRequest -Uri 'https://x'", "curl", "curl -H", "curl -H 'nocolon'", "'unterminated", "\$'", "a: b: c")) {
            val p = CurlParser.parse(text)
            assertFalse(p.complete)
            assertEquals(5, p.missing.size)
        }
    }

    @Test fun anInvalidValueIsRefusedWithoutEcho() {
        val p = CurlParser.parse("curl 'https://x' -H 'authorization: $token' -H 'user-agent: café' -H 'x-super-properties: $props' -H 'x-discord-locale: en' -H 'x-discord-timezone: UTC'")
        assertTrue(p.complete)
        assertNull(p.toHeaderSet(1L)) // HeaderSet refuses the non-ASCII value
    }

    @Test fun codecRoundTripsAndRefusesBadBytes() {
        val h = HeaderSet(token, ua, props, "en-US", "UTC", 42L)
        val back = CredentialCodec.decode(CredentialCodec.encode(h))
        assertNotNull(back)
        assertEquals(token, back!!.authorization); assertEquals(42L, back.capturedAt)
        assertNull(CredentialCodec.decode("not json".toByteArray()))
        assertNull(CredentialCodec.decode("{\"authorization\":\"x\"}".toByteArray()))
        assertNull(CredentialCodec.decode(ByteArray(0)))
        val status = CredentialStatus(capturedAt = 0L, ageMs = 15L * 24 * 3_600_000)
        assertTrue(status.stale); assertEquals(15L, status.ageDays)
        assertFalse(CredentialStatus(0L, 13L * 24 * 3_600_000).stale)
    }
}
