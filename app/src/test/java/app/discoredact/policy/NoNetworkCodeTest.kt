package app.discoredact.policy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 1 has no network code (CLAUDE.md rules 1-3; M2 adds the one OkHttp client with its host interceptor).
 * Fails if main sources of :app or :core name a network API. M2 replaces this with the allowlist test.
 */
class NoNetworkCodeTest {
    private val forbidden = listOf(
        "okhttp3", "java.net.", "javax.net.", "HttpURLConnection", "HttpsURLConnection", "java.nio.channels",
        "android.webkit", "WebView", "DownloadManager", "Socket(", "URL(", "ktor", "retrofit",
    )

    @Test
    fun mainSourcesNameNoNetworkApi() {
        val roots = listOf(File("src/main"), File("../core/src/main"))
        assertTrue(roots.all { it.isDirectory })
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.flatMap { f ->
                val text = f.readText()
                forbidden.filter { text.contains(it) }.map { "${f.path}: $it" }
            }
        }
        assertTrue("network API in main sources: $hits", hits.isEmpty())
    }
}
