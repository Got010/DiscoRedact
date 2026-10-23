package app.discoredact.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import app.discoredact.core.executor.HeaderSet
import app.discoredact.core.signin.SignInCapture
import app.discoredact.core.signin.SignInHosts
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Ends a sign-in page: everything it stored is deleted and the WebView is destroyed (CLAUDE.md rule 9). */
object SignInSession {
    /**
     * Main thread only. Every step is tried even if an earlier one failed; returns the names of the steps that threw
     * (never a value). The cookie and storage calls are process-wide, so they also cover the page's frames.
     */
    fun clear(webView: WebView, context: Context): List<String> {
        val failed = ArrayList<String>()
        fun step(name: String, block: () -> Unit) {
            if (runCatching(block).isFailure) failed.add(name)
        }
        step("stop") { webView.stopLoading() }
        step("blank page") { webView.loadUrl("about:blank") }
        step("history") { webView.clearHistory() }
        step("cache") { webView.clearCache(true) }
        step("form data") { webView.clearFormData() }
        step("cookies") {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        step("site storage") { WebStorage.getInstance().deleteAllData() }
        step("HTTP auth") { WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword() }
        step("destroy") { webView.destroy() }
        return failed
    }
}

/** What the page's requests to Discord's API showed so far; names only. */
sealed interface PageStatus {
    data object Waiting : PageStatus
    data object HeadersHidden : PageStatus
    data class Lacking(val fields: List<String>) : PageStatus
}

/**
 * One sign-in page: a WebView on Discord's login page behind the host filter of [SignInHosts], and the capture of
 * [SignInCapture] on the page's own requests. The app runs nothing in the page and reads request headers only; the
 * header map is read once per request and dropped, never logged, kept or shown. The callbacks run on the main thread;
 * [onCaptured] at most once.
 */
class SignInPage(
    private val onBlocked: (String) -> Unit,
    private val onStatus: (PageStatus) -> Unit,
    private val onCaptured: (HeaderSet) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** Set by the capture or the end: from then on every request is refused. */
    private val ended = AtomicBoolean(false)
    private val labels = ConcurrentHashMap.newKeySet<String>()
    private val hidden = AtomicInteger(0)
    @Volatile private var exposed = false
    @Volatile private var lastStatus: PageStatus? = null
    @Volatile private var userAgent = ""
    private var webView: WebView? = null

    /** Main thread only: makes the WebView and starts the login page. */
    @SuppressLint("SetJavaScriptEnabled") // Discord's login page needs script and DOM storage; the app adds no script of its own.
    fun open(context: Context): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        userAgent = view.settings.userAgentString.orEmpty()
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(request)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = refuses(request)
        }
        // A service worker's requests do not pass the page's client: the same filter applies to them.
        current = this
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.allowFileAccess = false
            serviceWorkerWebSettings.allowContentAccess = false
            setServiceWorkerClient(WorkerFilter)
        }
        webView = view
        view.loadUrl(SignInHosts.LOGIN_PAGE)
        return view
    }

    /** Main thread only. Ends the page: refuses everything from now on, then clears and destroys it. Safe to call twice. */
    fun end(context: Context): List<String> {
        ended.set(true)
        if (current === this) current = null
        val view = webView ?: return emptyList()
        webView = null
        return SignInSession.clear(view, context)
    }

    /** Called on a WebView thread for every request the page or its frames make. */
    internal fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return refused()
        if (ended.get()) return refused()
        if (!SignInHosts.allows(url)) {
            block(url)
            return refused()
        }
        if (!SignInCapture.isApi(url)) return null
        val outcome = SignInCapture.inspect(url, request.requestHeaders ?: emptyMap(), userAgent, System.currentTimeMillis())
        when (outcome) {
            is SignInCapture.Outcome.Captured ->
                if (ended.compareAndSet(false, true)) {
                    val headers = outcome.headers
                    main.post { onCaptured(headers) }
                }
            is SignInCapture.Outcome.Unusable -> {
                exposed = true
                status(PageStatus.Lacking(outcome.missing + outcome.invalid))
            }
            SignInCapture.Outcome.NotSignedIn -> exposed = true
            SignInCapture.Outcome.HeadersHidden ->
                if (hidden.incrementAndGet() >= HIDDEN_BEFORE_NOTICE && !exposed) status(PageStatus.HeadersHidden)
            SignInCapture.Outcome.NotApi -> Unit
        }
        // The page's own request goes ahead untouched.
        return null
    }

    /** Navigations: true cancels one that the filter refuses. */
    private fun refuses(request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return true
        if (ended.get()) return true
        if (SignInHosts.allows(url)) return false
        block(url)
        return true
    }

    /** The host name only reaches the screen, once per host; nothing else about the request is kept. */
    private fun block(url: String) {
        val label = SignInHosts.blockedLabel(url)
        if (labels.size < MAX_LABELS && labels.add(label)) main.post { onBlocked(label) }
    }

    private fun status(s: PageStatus) {
        if (s == lastStatus) return
        lastStatus = s
        main.post { onStatus(s) }
    }

    private object WorkerFilter : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
            val page = current ?: return refused()
            return page.intercept(request) // null lets an admitted request through
        }
    }

    companion object {
        /** API requests with none of Discord's headers visible before the screen says the headers are hidden. */
        private const val HIDDEN_BEFORE_NOTICE = 3
        private const val MAX_LABELS = 40

        /** The page that is open, if any; the service-worker filter refuses everything when none is. */
        @Volatile private var current: SignInPage? = null

        /** An empty 403 with no body. */
        private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
