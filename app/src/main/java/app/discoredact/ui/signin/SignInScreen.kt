@file:OptIn(ExperimentalMaterial3Api::class)

package app.discoredact.ui.signin

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.discoredact.core.executor.HeaderSet
import app.discoredact.core.signin.SignInGrace
import app.discoredact.core.signin.SignInHosts
import app.discoredact.ui.AppViewModel
import kotlinx.coroutines.launch

private enum class Phase { Notice, Opening, Page, Ended }

/**
 * Sign in on the phone (B34 Form A): Discord's own login page, behind the host filter, until one of its requests to
 * Discord's API carries the five headers. They are stored as a cURL import stores them; then everything the page
 * stored is deleted. Nothing loads before the tap; leaving the screen ends the page, and the app going to the background
 * ends it after a two-minute grace.
 */
@Composable
fun SignInScreen(vm: AppViewModel) {
    var phase by remember { mutableStateOf(Phase.Notice) }
    var status by remember { mutableStateOf("Nothing is loaded yet.") }
    val blocked = remember { mutableStateListOf<String>() }
    var stored by remember { mutableStateOf(false) }
    // What the Ended phase says; it claims a deletion only when the clear reported no failed step.
    var endText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun cleared(failed: List<String>): String =
        if (failed.isEmpty()) "The page is closed and everything it stored was deleted." else vm.signInClearNote(failed)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to Discord") },
                navigationIcon = { IconButton(onClick = { vm.back() }) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (blocked.isNotEmpty()) {
                    Text("Blocked: ${blocked.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            HorizontalDivider()
            when (phase) {
                Phase.Notice -> Notice(
                    onLoad = {
                        phase = Phase.Opening
                        status = "Opening the sign-in page."
                        scope.launch {
                            if (vm.signInStorageClean()) {
                                status = statusLine(PageStatus.Waiting)
                                phase = Phase.Page
                            } else {
                                // Fail closed (CLAUDE.md rule 19): never load over what an earlier page left.
                                status = "Nothing was loaded."
                                endText = "What an earlier sign-in page left on disk could not be deleted. Wipe everything (Settings) removes it; then try again."
                                phase = Phase.Ended
                            }
                        }
                    },
                )
                Phase.Opening -> Unit
                Phase.Page -> Page(
                    onBlocked = { label -> if (label !in blocked) blocked.add(label) },
                    onStatus = { s -> status = statusLine(s) },
                    onCaptured = { headers: HeaderSet, failed: List<String> ->
                        // The page has already been ended and cleared; the set is stored only now.
                        phase = Phase.Ended
                        stored = true
                        endText = cleared(failed)
                        status = "Captured; storing."
                        vm.storeSignIn(headers, failed) { ok, line -> stored = ok; status = line }
                    },
                    onBackground = { failed ->
                        phase = Phase.Ended
                        status = "Nothing was captured."
                        endText = listOfNotNull(
                            "The page was closed after two minutes in the background; start again.",
                            failed.takeIf { it.isNotEmpty() }?.let { vm.signInClearNote(it) },
                        ).joinToString(" ")
                    },
                    onClearFailed = vm::signInClearFailed,
                    modifier = Modifier.weight(1f).fillMaxWidth().imePadding(),
                )
                Phase.Ended -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(endText, style = MaterialTheme.typography.bodySmall)
                    if (!stored) {
                        Button(onClick = { blocked.clear(); phase = Phase.Notice; status = "Nothing is loaded yet." }, modifier = Modifier.fillMaxWidth()) {
                            Text("Start again")
                        }
                    }
                }
            }
        }
    }
}

private fun statusLine(s: PageStatus): String = when (s) {
    PageStatus.Waiting -> "Sign in on Discord's page below. Waiting for a signed-in request to Discord's API."
    PageStatus.HeadersHidden -> "The page's requests did not expose the headers; use the cURL paste. Nothing was captured."
    is PageStatus.Lacking -> "A signed-in request lacked ${s.fields.joinToString(", ")}; waiting for another. Nothing was captured yet."
}

@Composable
private fun Notice(onLoad: () -> Unit) {
    Column(
        Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Before the page loads", style = MaterialTheme.typography.titleMedium)
        Text(
            "This is Discord's own sign-in page. Sign in as in any browser: Discord's page handles the password, any " +
                "second factor and any captcha. The app never sees the password; it reads only the five session headers " +
                "(Authorization, User-Agent, X-Super-Properties, X-Discord-Locale, X-Discord-Timezone) from the page's " +
                "requests to Discord's API, and stores them encrypted as a cURL import does.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "The page may load only over HTTPS from ${SignInHosts.HOSTS.joinToString(", ")} and their subdomains. Every " +
                "other request is refused, and its host name is listed under Blocked.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "When the capture ends or when you close this screen, the page is closed and everything it stored (cookies, " +
                "site storage, cache, history, form data) is deleted. If you need a code from another app, you have two " +
                "minutes: the page is closed after two minutes in the background. Nothing loads before you tap the button.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) { Text("Load the sign-in page") }
    }
}

/**
 * The page itself. Leaving composition for any reason (Close, back, rotation) ends it. When the app leaves the screen
 * (ON_STOP: home, another app, the screen turning off) the page lives on for [SignInGrace.GRACE_MS], so a code can be
 * read in another app, and is then ended; coming back in time (ON_START) cancels that. A capture in the grace stores
 * normally.
 */
@Composable
private fun Page(
    onBlocked: (String) -> Unit,
    onStatus: (PageStatus) -> Unit,
    onCaptured: (HeaderSet, List<String>) -> Unit,
    onBackground: (List<String>) -> Unit,
    onClearFailed: (List<String>) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val page = remember { SignInPage(onBlocked, onStatus, onCaptured) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(page, lifecycle) {
        val main = Handler(Looper.getMainLooper())
        // When the app left the screen, on the elapsed-realtime clock (it runs in deep sleep); null while in front.
        var stoppedAt: Long? = null
        // The one pending end: posted on ON_STOP, removed on ON_START, ON_RESUME and disposal. Handler time stops in
        // deep sleep, so coming back also checks the clock and ends a page whose grace ran out while the phone slept.
        val endIfExpired = Runnable {
            if (page.isOpen && SignInGrace.expired(stoppedAt, SystemClock.elapsedRealtime())) {
                stoppedAt = null
                onBackground(page.end(context))
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> if (page.isOpen) {
                    stoppedAt = SystemClock.elapsedRealtime()
                    main.removeCallbacks(endIfExpired)
                    main.postDelayed(endIfExpired, SignInGrace.GRACE_MS)
                }
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    main.removeCallbacks(endIfExpired)
                    endIfExpired.run()
                    stoppedAt = null
                }
                else -> Unit
            }
            Unit
        }
        lifecycle.addObserver(observer)
        onDispose {
            main.removeCallbacks(endIfExpired)
            lifecycle.removeObserver(observer)
            val failed = page.end(context)
            if (failed.isNotEmpty()) onClearFailed(failed)
        }
    }
    AndroidView(factory = { c -> page.open(c) }, modifier = modifier)
}

