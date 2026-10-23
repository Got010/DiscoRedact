package app.discoredact.core.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInHostsTest {
    @Test fun theListIsExactlyTheFourHosts() {
        assertEquals(listOf("discord.com", "discordapp.com", "discordapp.net", "hcaptcha.com"), SignInHosts.HOSTS)
        assertTrue(SignInHosts.allows(SignInHosts.LOGIN_PAGE))
    }

    @Test fun eachAdmittedHostAndItsSubdomainsPass() {
        for (url in listOf(
            "https://discord.com/login",
            "https://discord.com/api/v9/auth/login",
            "https://discord.com/assets/abc.js",
            "https://discordapp.com/",
            "https://cdn.discordapp.com/avatars/1/2.png",
            "https://discordapp.net",
            "https://media.discordapp.net/x?y=1#z",
            "https://hcaptcha.com/checksiteconfig",
            "https://newassets.hcaptcha.com/captcha/v1/abc/static/hcaptcha.html#frame=checkbox",
            "https://api.hcaptcha.com/getcaptcha",
            "https://a.b.discord.com/",
            "HTTPS://Discord.COM/login",
        )) assertTrue(url, SignInHosts.allows(url))
    }

    @Test fun everythingElseIsRefused() {
        for (url in listOf(
            // look-alikes and other hosts
            "https://discord.com.evil.example/",
            "https://evil-discord.com/",
            "https://notdiscord.com/",
            "https://xdiscordapp.com/",
            "https://discordapp.com.evil.example/x",
            "https://hcaptcha.com.evil.example/",
            "https://discord.gg/",
            "https://gateway.discord.gg/",
            "https://evil.example/discord.com",
            "https://evil.example/?u=https://discord.com/",
            // other schemes
            "http://discord.com/login",
            "wss://gateway.discord.com/",
            "ftp://discord.com/",
            "blob:https://discord.com/0b5c-11",
            "data:text/html,discord.com",
            "javascript:alert(1)",
            "about:blank",
            "file:///data/data/x",
            "content://discord.com/x",
            "intent://discord.com/#Intent;scheme=https;end",
            // IP addresses
            "https://162.159.135.232/api/v9/x",
            "https://[2606:4700::6810:1]/",
            "https://0x7f000001/",
            // ports, even the default one
            "https://discord.com:8443/login",
            "https://discord.com:443/login",
            "https://discord.com:/login",
            // user info tricks
            "https://discord.com@evil.example/",
            "https://user:pass@discord.com/",
            "https://evil.example\\@discord.com/",
            "https://evil.example#@discord.com/",
            "https://evil.example?@discord.com/",
            "https://evil.example\\.discord.com/",
            // malformed
            "https://discord.com./",
            "https://.discord.com/",
            "https://discord..com/",
            "https://disc%6Frd.com/",
            "https://disc ord.com/",
            "https:/discord.com/",
            "https:discord.com",
            "https://",
            "discord.com",
            "//discord.com/",
            "",
            ":",
        )) assertFalse(url, SignInHosts.allows(url))
    }

    @Test fun theBlockedLabelIsTheHostOnly() {
        assertEquals("evil.example", SignInHosts.blockedLabel("https://evil.example/path/secret?q=token#frag"))
        assertEquals("evil.example", SignInHosts.blockedLabel("https://user:pw@evil.example:8443/x"))
        assertEquals("evil.example", SignInHosts.blockedLabel("https://discord.com@evil.example/"))
        assertEquals("http:discord.com", SignInHosts.blockedLabel("http://discord.com/login?x=1"))
        assertEquals("wss:gateway.discord.gg", SignInHosts.blockedLabel("wss://gateway.discord.gg/?v=9"))
        assertEquals("blob:", SignInHosts.blockedLabel("blob:https://discord.com/0b5c-11"))
        assertEquals("data:", SignInHosts.blockedLabel("data:image/png;base64,AAAA"))
        assertEquals("162.159.135.232", SignInHosts.blockedLabel("https://162.159.135.232/api"))
        assertEquals("https:?", SignInHosts.blockedLabel("https://disc%6Frd.com/"))
        assertEquals("?", SignInHosts.blockedLabel("no scheme here"))
        assertEquals("?", SignInHosts.blockedLabel(""))
    }
}
