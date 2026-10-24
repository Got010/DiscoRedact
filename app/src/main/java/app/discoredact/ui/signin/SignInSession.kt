package app.discoredact.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceError
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

/**
 * What the page did so far: how the login page's own document loaded, then what its requests to Discord's API showed.
 * Names and counts only: never an address, a header or page content.
 */
sealed interface PageStatus {
    data object Waiting : PageStatus

    /** The login page's document is loading; [admitted] requests have passed the host filter so far. */
    data class Loading(val admitted: Int) : PageStatus

    /** The document finished loading; [admitted] requests have passed the filter so far (the count keeps growing). */
    data class Loaded(val admitted: Int) : PageStatus

    /** The document did not load: [what] is the error's name, an HTTP status, or the renderer's end; never an address. */
    data class Failed(val what: String) : PageStatus

    data object HeadersHidden : PageStatus
    data class Lacking(val fields: List<String>) : PageStatus
}

/**
 * One sign-in page: a WebView on Discord's login page behind the host filter of [SignInHosts], and the capture of
 * [SignInCapture] on the page's own requests. The app runs nothing in the page and reads request headers only; the
 * header map is read once per request and dropped, never logged, kept or shown. The callbacks run on the main thread.
 * [onCaptured] runs at most once, after the page has been ended, with the clear's failed steps (names only); it does
 * not run at all if the page was ended first (Close, back, the app leaving the screen).
 */
class SignInPage(
    private val onBlocked: (String) -> Unit,
    private val onStatus: (PageStatus) -> Unit,
    private val onCaptured: (HeaderSet, List<String>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    /** Set by the capture or the end: from then on every request is refused. */
    private val ended = AtomicBoolean(false)
    private val labels = ConcurrentHashMap.newKeySet<String>()
    private val hidden = AtomicInteger(0)
    private val admitted = AtomicInteger(0)
    @Volatile private var documentStarted = false
    @Volatile private var documentFinished = false
    @Volatile private var documentFailed = false
    @Volatile private var exposed = false
    @Volatile private var lastStatus: PageStatus? = null
    @Volatile private var userAgent = ""
    private var webView: WebView? = null
    private var openedIn: Context? = null

    /** Main thread: the failed clear steps of an end nobody has reported yet (a gone renderer); the next [end] returns them. */
    private var unreported: List<String> = emptyList()

    /** Main thread only: true from [open] until [end]. */
    val isOpen: Boolean get() = webView != null

    /** Main thread only: makes the WebView and starts the login page. */
    @SuppressLint("SetJavaScriptEnabled") // Discord's login page needs script and DOM storage; the app adds no script of its own.
    fun open(context: Context): WebView {
        val view = WebView(context)
        // Compose adds the view with wrap-content params, and Chromium lays the page out from those, not from the size
        // the view is measured at: a wrap-content height means a zero-height page, and a page built on `height: 100%`
        // (Discord's client) then draws nothing. Match the parent so the page gets the view's real height.
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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

            // The document's own loading, so a page that stays blank says why (the address is not repeated: it is the
            // login page or a navigation the filter admitted; only names and counts reach the screen).
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (ended.get()) return
                documentStarted = true
                documentFinished = false
                loadStatus()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (ended.get()) return
                documentFinished = true
                loadStatus()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (ended.get() || !request.isForMainFrame) return
                documentFailed = true
                status(PageStatus.Failed("error ${error.errorCode}, ${safeText(error.description?.toString())}"))
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (ended.get() || !request.isForMainFrame) return
                documentFailed = true
                status(PageStatus.Failed("HTTP ${errorResponse.statusCode}"))
            }

            // Returning true keeps the app alive when the WebView's renderer is gone. Everything is refused from now on;
            // the page is ended (cleared and destroyed) once this callback has returned, not from inside it, and the
            // screen says so. The clear's failed steps are kept for the next [end] (Close), which reports them.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (ended.getAndSet(true)) return true
                documentFailed = true
                val what = if (detail.didCrash()) "the page's renderer crashed" else "the page's renderer was stopped by the system"
                main.post {
                    val context = openedIn
                    if (context != null) unreported = end(context)
                    onStatus(PageStatus.Failed(what))
                }
                return true
            }
        }
        // A service worker's requests do not pass the page's client: the same filter applies to them.
        current = this
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.allowFileAccess = false
            serviceWorkerWebSettings.allowContentAccess = false
            setServiceWorkerClient(WorkerFilter)
        }
        webView = view
        openedIn = context
        view.loadUrl(SignInHosts.LOGIN_PAGE)
        return view
    }

    /** Main thread only. Ends the page: refuses everything from now on, then clears and destroys it. Safe to call twice. */
    fun end(context: Context): List<String> {
        ended.set(true)
        if (current === this) current = null
        val view = webView ?: return unreported.also { unreported = emptyList() }
        webView = null
        openedIn = null
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
        admitted.incrementAndGet()
        if (!SignInCapture.isApi(url)) {
            loadStatus()
            return null
        }
        val outcome = SignInCapture.inspect(url, request.requestHeaders ?: emptyMap(), userAgent, System.currentTimeMillis())
        when (outcome) {
            is SignInCapture.Outcome.Captured ->
                if (ended.compareAndSet(false, true)) {
                    val headers = outcome.headers
                    main.post { captured(headers) }
                }
            is SignInCapture.Outcome.Unusable -> {
                exposed = true
                status(PageStatus.Lacking(outcome.missing + outcome.invalid))
            }
            SignInCapture.Outcome.NotSignedIn -> {
                exposed = true
                loadStatus()
            }
            SignInCapture.Outcome.HeadersHidden ->
                if (hidden.incrementAndGet() >= HIDDEN_BEFORE_NOTICE && !exposed) status(PageStatus.HeadersHidden)
            SignInCapture.Outcome.NotApi -> Unit
        }
        // The page's own request goes ahead untouched.
        return null
    }

    /** Main thread. Ends the page first, so the set is stored only from a page that was cleared and never after it was closed. */
    private fun captured(headers: HeaderSet) {
        val context = openedIn ?: return
        onCaptured(headers, end(context))
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

    /**
     * One status at a time, in order, from any thread; a status posted for a page that has since ended is dropped, so
     * nothing overwrites "Captured" or the Ended text.
     */
    @Synchronized
    private fun status(s: PageStatus) {
        if (s == lastStatus) return
        lastStatus = s
        main.post { if (!ended.get()) onStatus(s) }
    }

    /** The document's loading state with the admitted count; silent once it failed or a capture outcome is showing. */
    @Synchronized
    private fun loadStatus() {
        if (documentFailed || !documentStarted) return
        val last = lastStatus
        if (last is PageStatus.Lacking || last == PageStatus.HeadersHidden) return
        val n = admitted.get()
        status(if (documentFinished) PageStatus.Loaded(n) else PageStatus.Loading(n))
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

        /** Printable ASCII only, at most 80 characters: an error description, never a page's own text. */
        private fun safeText(s: String?): String {
            val t = s.orEmpty().filter { it in ' '..'~' }.take(80)
            return t.ifEmpty { "no description" }
        }

        /** An empty 403 with no body. */
        private fun refused() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }
}
