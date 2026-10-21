package app.discoredact.core.executor

/**
 * Reads the headers out of a browser's "Copy as cURL" text (spec section 7). Only the five fields the app sends are
 * taken; the URL, cookies and every other header are discarded. No error message ever contains input text.
 *
 * Accepted shapes: Chrome's "Copy as cURL (bash)" (single quotes, `$'...'` ANSI-C quotes, backslash line
 * continuations, `--compressed`), Chrome's "(cmd)" (double quotes, `^` continuations), Firefox's (the same bash
 * shape), and a bare list of `Name: value` lines pasted from the request headers panel.
 */
object CurlParser {
    val WANTED: List<String> = listOf("authorization", "user-agent", "x-super-properties", "x-discord-locale", "x-discord-timezone")

    /** What was found, by lower-case header name, and which of the five are missing. Values are never printed. */
    class Parsed(val found: Map<String, String>, val missing: List<String>) {
        val complete: Boolean get() = missing.isEmpty()

        /** The header set, or null when a field is missing or invalid. */
        fun toHeaderSet(capturedAt: Long): HeaderSet? {
            if (!complete) return null
            return try {
                HeaderSet(
                    authorization = found.getValue("authorization"),
                    userAgent = found.getValue("user-agent"),
                    superProperties = found.getValue("x-super-properties"),
                    locale = found.getValue("x-discord-locale"),
                    timezone = found.getValue("x-discord-timezone"),
                    capturedAt = capturedAt,
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        override fun toString(): String = "Parsed(found=${found.keys.sorted()}, missing=$missing)"
    }

    fun parse(text: String): Parsed {
        val headers = LinkedHashMap<String, String>()
        val tokens = tokenize(text)
        var i = 0
        var sawCurl = false
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t == "curl" || t.endsWith("/curl") || t.equals("curl.exe", ignoreCase = true) -> sawCurl = true
                t == "-H" || t == "--header" -> {
                    if (i + 1 < tokens.size) addHeader(headers, tokens[i + 1])
                    i++
                }
                t.startsWith("--header=") -> addHeader(headers, t.removePrefix("--header="))
                t.startsWith("-H") && t.length > 2 -> addHeader(headers, t.substring(2))
                else -> Unit
            }
            i++
        }
        if (!sawCurl || headers.isEmpty()) {
            // A bare paste of the headers panel: one `Name: value` per line.
            for (line in text.lines()) addHeader(headers, line.trim().removePrefix("-H ").trim('\'', '"', ' '))
        }
        val found = WANTED.filter { headers[it]?.isNotBlank() == true }.associateWith { headers.getValue(it) }
        return Parsed(found, WANTED.filter { it !in found })
    }

    private fun addHeader(into: MutableMap<String, String>, raw: String) {
        val colon = raw.indexOf(':')
        if (colon <= 0) return
        val name = raw.substring(0, colon).trim().lowercase()
        if (name.isEmpty() || name.any { it.isWhitespace() }) return
        val value = raw.substring(colon + 1).trim()
        if (name in WANTED && value.isNotEmpty()) into[name] = value
    }

    /** A small shell-style tokenizer: single quotes, double quotes, `$'...'`, backslash escapes, `\` and `^` line continuations. */
    internal fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var hasToken = false
        var i = 0
        fun flush() {
            if (hasToken) out.add(cur.toString())
            cur.setLength(0)
            hasToken = false
        }
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' && i > 0 && text[i - 1] == '$' && (cur.isEmpty() || cur.last() == '$') -> {
                    // $'...' ANSI-C quoting
                    if (cur.isNotEmpty()) cur.setLength(cur.length - 1)
                    hasToken = true
                    i++
                    while (i < text.length && text[i] != '\'') {
                        if (text[i] == '\\' && i + 1 < text.length) {
                            i++
                            when (val e = text[i]) {
                                'n' -> cur.append('\n'); 't' -> cur.append('\t'); 'r' -> cur.append('\r')
                                '\\' -> cur.append('\\'); '\'' -> cur.append('\''); '"' -> cur.append('"')
                                'x' -> {
                                    val hex = text.substring(i + 1, minOf(i + 3, text.length)).takeWhile { it.isLetterOrDigit() }
                                    val v = hex.toIntOrNull(16)
                                    if (v != null) { cur.append(v.toChar()); i += hex.length } else cur.append('x')
                                }
                                else -> { cur.append('\\'); cur.append(e) }
                            }
                        } else cur.append(text[i])
                        i++
                    }
                    i++
                }
                c == '\'' -> {
                    hasToken = true
                    i++
                    while (i < text.length && text[i] != '\'') { cur.append(text[i]); i++ }
                    i++
                }
                c == '"' -> {
                    hasToken = true
                    i++
                    while (i < text.length && text[i] != '"') {
                        if (text[i] == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`") { i++ }
                        cur.append(text[i]); i++
                    }
                    i++
                }
                c == '\\' && i + 1 < text.length && (text[i + 1] == '\n' || text[i + 1] == '\r') -> {
                    i += if (text[i + 1] == '\r' && i + 2 < text.length && text[i + 2] == '\n') 3 else 2
                }
                c == '^' && i + 1 < text.length && (text[i + 1] == '\n' || text[i + 1] == '\r') -> {
                    i += if (text[i + 1] == '\r' && i + 2 < text.length && text[i + 2] == '\n') 3 else 2
                }
                c == '\\' && i + 1 < text.length -> { cur.append(text[i + 1]); hasToken = true; i += 2 }
                c.isWhitespace() -> { flush(); i++ }
                else -> { cur.append(c); hasToken = true; i++ }
            }
        }
        flush()
        return out
    }
}
