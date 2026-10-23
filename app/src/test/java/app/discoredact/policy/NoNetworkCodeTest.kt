package app.discoredact.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one network client lives in `:net` (CLAUDE.md rules 1-3). Main sources of `:app` and `:core` name no network
 * API at all; `:net` names OkHttp and nothing else network-shaped, and holds the production base URL literal.
 * The one exception is the sign-in screen's WebView (rule 2, B34 Form A), in exactly the files named below.
 */
class NoNetworkCodeTest {
    private val forbidden = listOf(
        "okhttp3", "java.net.", "javax.net.", "HttpURLConnection", "HttpsURLConnection", "java.nio.channels",
        "android.webkit", "WebView", "DownloadManager", "Socket(", "URL(", "ktor", "retrofit",
    )

    /**
     * The sign-in page (the WebView, its host filter and capture, its clear) may name `android.webkit` and `WebView`;
     * the WebView-free clearer Graph calls names `WebView` only, as a directory name. Paths under `src/main`.
     */
    private val webViewAllowed = mapOf(
        "java/app/discoredact/ui/signin/SignInSession.kt" to setOf("android.webkit", "WebView"),
        "java/app/discoredact/data/SignInStorage.kt" to setOf("WebView"),
    )

    private fun sources(root: File) = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }

    @Test
    fun appAndCoreMainSourcesNameNoNetworkApi() {
        val app = File("src/main")
        val roots = listOf(app, File("../core/src/main"))
        assertTrue(roots.all { it.isDirectory })
        val hits = roots.flatMap { root ->
            sources(root).flatMap { f ->
                val text = f.readText()
                val allowed = if (root == app) webViewAllowed[f.relativeTo(root).invariantSeparatorsPath].orEmpty() else emptySet()
                forbidden.filter { it !in allowed && text.contains(it) }.map { "${f.path}: $it" }
            }.toList()
        }
        assertTrue("network API in main sources: $hits", hits.isEmpty())
    }

    @Test
    fun theWebViewExceptionNamesFilesThatExist() {
        for (path in webViewAllowed.keys) assertTrue(path, File("src/main", path).isFile)
        assertTrue(File("src/main", "java/app/discoredact/ui/signin/SignInSession.kt").readText().contains("import android.webkit.WebView"))
    }

    /** Section 1 of the Form A brief and rule 9: no script of the app's in the page, no download, no log, no page content read. */
    @Test
    fun theSignInPageRunsNoScriptAndLogsNothing() {
        val banned = listOf(
            "addJavascriptInterface", "evaluateJavascript", "javascript:", "loadData", "postWebMessage", "createWebMessageChannel",
            "addDocumentStartJavaScript", "addWebMessageListener", "setDownloadListener", "WebChromeClient", "getCookie",
            "Log.", "println", "printStackTrace", "Toast",
            // Fail-open or exposure switches: debugging over adb, a TLS-error override, page archives, a posted form.
            "setWebContentsDebuggingEnabled", "onReceivedSslError", ".proceed(", "saveWebArchive", "postUrl",
        )
        val files = sources(File("src/main/java/app/discoredact/ui/signin")).toList() + File("src/main/java/app/discoredact/data/SignInStorage.kt")
        assertTrue(files.size >= 3)
        val hits = files.flatMap { f -> val text = f.readText(); banned.filter { text.contains(it) }.map { "${f.path}: $it" } }
        assertEquals(emptyList<String>(), hits)
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
