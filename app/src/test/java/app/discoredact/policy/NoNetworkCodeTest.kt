package app.discoredact.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one network client lives in `:net` (CLAUDE.md rules 1-3). Main sources of `:app` and `:core` name no network
 * API at all; `:net` names OkHttp and nothing else network-shaped, and holds the production base URL literal.
 */
class NoNetworkCodeTest {
    private val forbidden = listOf(
        "okhttp3", "java.net.", "javax.net.", "HttpURLConnection", "HttpsURLConnection", "java.nio.channels",
        "android.webkit", "WebView", "DownloadManager", "Socket(", "URL(", "ktor", "retrofit",
    )

    private fun sources(root: File) = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }

    @Test
    fun appAndCoreMainSourcesNameNoNetworkApi() {
        val roots = listOf(File("src/main"), File("../core/src/main"))
        assertTrue(roots.all { it.isDirectory })
        val hits = roots.flatMap { root ->
            sources(root).flatMap { f ->
                val text = f.readText()
                forbidden.filter { text.contains(it) }.map { "${f.path}: $it" }
            }.toList()
        }
        assertTrue("network API in main sources: $hits", hits.isEmpty())
    }

    @Test
    fun netNamesOnlyOkHttpAndTheProductionBaseUrl() {
        val root = File("../net/src/main")
        assertTrue(root.isDirectory)
        val texts = sources(root).map { it.readText() }.toList()
        val others = forbidden.filter { it != "okhttp3" }.filter { needle -> texts.any { it.contains(needle) } }
        assertEquals("only OkHttp may be named in :net", emptyList<String>(), others)
        assertTrue(texts.any { it.contains("const val PRODUCTION_BASE_URL = \"https://discord.com/api/v9\"") })
        assertTrue(texts.any { it.contains("const val DISCORD_HOST = \"discord.com\"") })
        // No logging interceptor and no other host literal anywhere in :net.
        assertTrue(texts.none { it.contains("HttpLoggingInterceptor") || it.contains("println") || it.contains("Log.") })
        val hosts = Regex("https?://([a-z0-9.-]+)").findAll(texts.joinToString("\n")).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("discord.com"), hosts)
    }
}
