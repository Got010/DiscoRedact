package app.discoredact.core.signin

/**
 * The sign-in screen's own allowlist (CLAUDE.md rule 2): the page may load only over https and only from these hosts
 * or their subdomains. Every other request, navigation included, is refused. The app's own client keeps its separate
 * one-host allowlist in `:net`; this list never reaches it. Each host has a note in `gate/baseline.json`.
 *
 * Pure string handling, no Android and no network API, so the JVM tests cover every case.
 */
object SignInHosts {
    /** The page the screen loads, and nothing else on its own. */
    const val LOGIN_PAGE = "https://discord.com/login"

    val HOSTS: List<String> = listOf(
        // Discord's web client: the login page itself, its scripts, styles and fonts, and its API.
        "discord.com",
        // Discord's own CDN (cdn.discordapp.com: avatars, icons the login page shows).
        "discordapp.com",
        // Discord's own media host (media.discordapp.net, images.discordapp.net).
        "discordapp.net",
        // The captcha Discord's login may show (hCaptcha's own frames, scripts and images).
        "hcaptcha.com",
    )

    /** True only for an https address whose host is one of [HOSTS] or a subdomain of one, with no port and no user info. */
    fun allows(url: String): Boolean {
        val a = parse(url) ?: return false
        if (!a.scheme.equals("https", ignoreCase = true)) return false
        if (a.userInfo || a.port != null) return false
        val host = a.host
        if (!isDnsName(host)) return false
        return HOSTS.any { h -> host == h || (host.length > h.length && host.endsWith(h) && host[host.length - h.length - 1] == '.') }
    }

    /**
     * What the Blocked list shows for a refused address: the host name only (with the scheme in front when it is not
     * https, so "http:" and "blob:" read as such). Never a path, a query, a fragment or user info.
     */
    fun blockedLabel(url: String): String {
        val a = parse(url) ?: return "?"
        val scheme = a.scheme.lowercase()
        val host = a.host.takeIf { it.isNotEmpty() && it.length <= 253 && it.all(::hostChar) }
        return when {
            !a.hasAuthority -> "$scheme:"
            host == null -> "$scheme:?"
            scheme == "https" -> host
            else -> "$scheme:$host"
        }
    }

    /** The pieces [allows] and the capture need; the address is never copied anywhere else. */
    internal class Parts(val scheme: String, val hasAuthority: Boolean, val userInfo: Boolean, val host: String, val port: String?, val path: String)

    internal fun parse(url: String): Parts? {
        val colon = url.indexOf(':')
        if (colon <= 0) return null
        val scheme = url.substring(0, colon)
        if (!scheme[0].isLetter() || !scheme.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "+.-" }) return null
        if (!url.startsWith("//", colon + 1)) return Parts(scheme, false, false, "", null, "")
        val start = colon + 3
        // A backslash ends the authority as a slash does (browsers read it so in http and https addresses).
        var end = start
        while (end < url.length && url[end] !in "/?#\\") end++
        val authority = url.substring(start, end)
        val at = authority.lastIndexOf('@')
        val hostPort = if (at >= 0) authority.substring(at + 1) else authority
        val portColon = if (hostPort.startsWith("[")) hostPort.indexOf("]:").let { if (it < 0) -1 else it + 1 } else hostPort.lastIndexOf(':')
        val host = (if (portColon >= 0) hostPort.substring(0, portColon) else hostPort).lowercase()
        val port = if (portColon >= 0) hostPort.substring(portColon + 1) else null
        val pathEnd = url.indexOfAny(charArrayOf('?', '#'), end).let { if (it < 0) url.length else it }
        return Parts(scheme, true, at >= 0, host, port, url.substring(end, pathEnd))
    }

    private fun hostChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-' || c == '[' || c == ']' || c == ':'

    /** Letters, digits and hyphens in dot-separated labels, ending in a letter label: never an IP address. */
    private fun isDnsName(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') || !it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '-' } }) return false
        return labels.last().any { it in 'a'..'z' }
    }
}
