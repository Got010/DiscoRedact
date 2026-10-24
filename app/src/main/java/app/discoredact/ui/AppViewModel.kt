package app.discoredact.ui

import android.app.Application
import android.app.NotificationManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.discoredact.Graph
import app.discoredact.core.model.DmMode
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.pacing.QuietHours
import app.discoredact.core.selection.DeletePlan
import app.discoredact.core.selection.MessageFilter
import app.discoredact.core.selection.StartDecision
import app.discoredact.core.selection.StartRefusal
import app.discoredact.core.selection.StartRules
import app.discoredact.core.selection.FetchDecision
import app.discoredact.core.selection.FetchRefusal
import app.discoredact.core.history.FetchSession
import app.discoredact.core.report.LeftoversCsv
import app.discoredact.core.executor.CredentialState
import app.discoredact.core.executor.CurlParser
import app.discoredact.core.executor.Executor
import app.discoredact.core.executor.HeaderSet
import app.discoredact.data.AppSettings
import app.discoredact.data.ChannelSummary
import app.discoredact.data.EventEntity
import app.discoredact.data.FilterPreview
import app.discoredact.data.FreezeResult
import app.discoredact.data.ImportState
import app.discoredact.data.MessageRow
import app.discoredact.data.Repository
import app.discoredact.data.RunChannelLine
import app.discoredact.data.RunOptionsEntity
import app.discoredact.data.RunReport
import app.discoredact.data.StatusCount
import app.discoredact.data.SourceCount
import app.discoredact.data.FetchCounts
import app.discoredact.data.FetchView
import app.discoredact.data.RunProgress
import app.discoredact.work.RunScheduler
import app.discoredact.work.FetchScheduler
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/** How long the sign-in screen shows "Captured; closing." before it returns to the Credentials screen. */
private const val SIGN_IN_CLOSE_MS = 1_200L

sealed interface Route {
    data object Channels : Route
    data object Import : Route
    data class Browser(val channelId: Long) : Route
    data class Filters(val channelId: Long?) : Route
    data object Review : Route
    data class Frozen(val runId: Long, val total: Int, val channels: Int) : Route
    data object Settings : Route
    data object Run : Route
    data object Credentials : Route
    data object SignIn : Route
    data object Reports : Route

    /** "Fetch from Discord": the notice that comes first (what is read, what is not found, the estimate). */
    data object FetchNotice : Route

    /** The running, paused or finished fetch. */
    data object Fetch : Route
}

/** Loads a long list a page at a time (no Paging dependency). Pages load off the main thread. */
class Pager(private val scope: CoroutineScope, private val load: suspend (offset: Int, limit: Int) -> List<MessageRow>) {
    private val pages = mutableStateMapOf<Int, List<MessageRow>>()
    private val loading = HashSet<Int>()

    fun rowAt(index: Int): MessageRow? {
        val p = index / PAGE
        val page = pages[p]
        if (page == null && loading.add(p)) {
            scope.launch(Dispatchers.Main) {
                try {
                    pages[p] = load(p * PAGE, PAGE)
                } finally {
                    loading.remove(p)
                }
            }
        }
        return page?.getOrNull(index % PAGE)
    }

    /** Reloads the pages already shown, keeping the old rows on screen until the new ones arrive. */
    fun refresh() {
        for (p in pages.keys.toList()) scope.launch(Dispatchers.Main) { pages[p] = load(p * PAGE, PAGE) }
    }

    private companion object {
        const val PAGE = 100
    }
}

class BrowserState(val channelId: Long, private val vm: AppViewModel) {
    var query by mutableStateOf("")
        private set
    var pager by mutableStateOf(vm.newPager(channelId, null))
        private set
    val multi = mutableStateMapOf<Long, Boolean>()

    /** Adds the message to the multi-selection, or removes it when it is already there. */
    fun flip(id: Long) {
        if (multi.remove(id) == null) multi[id] = true
    }

    fun search(q: String) {
        query = q
        pager = vm.newPager(channelId, q)
    }
}

class ReviewState {
    var plan by mutableStateOf<DeletePlan?>(null)
    var pager by mutableStateOf<Pager?>(null)
    var computing by mutableStateOf(false)
    var freezing by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
}

class FilterState(val channelId: Long?) {
    var allDms by mutableStateOf(channelId == null)
    var from by mutableStateOf<LocalDate?>(null)
    var until by mutableStateOf<LocalDate?>(null)
    var keyword by mutableStateOf("")
    var attachmentsOnly by mutableStateOf(false)
    var preview by mutableStateOf<FilterPreview?>(null)
    var counting by mutableStateOf(false)
    var applying by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    /** Dates are local days: from the start of [from] to the end of [until]. */
    fun filter(zone: ZoneId = ZoneId.systemDefault()) = MessageFilter(
        fromMillis = from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        untilMillis = until?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        keyword = keyword,
        attachmentsOnly = attachmentsOnly,
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = Graph.get(app)
    private val repo: Repository get() = graph.repository

    /** Bumped after "Wipe everything", so every observed query reopens on the fresh database. */
    private val generation = MutableStateFlow(0)

    val backStack = mutableStateListOf<Route>(Route.Channels)
    val route: Route get() = backStack.last()

    val settings: StateFlow<AppSettings> = graph.settings.settings
    val summaries: StateFlow<List<ChannelSummary>?> =
        generation.flatMapLatest { repo.observeSummaries() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val contentCount: StateFlow<Int> =
        generation.flatMapLatest { repo.observeContentCount() }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val runCount: StateFlow<Int> =
        generation.flatMapLatest { repo.observeRunCount() }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ---- The current run (M2): an open one first, else the newest frozen one, else the last ended one. ----------

    private val scheduler = RunScheduler(app)
    private val currentRunId: StateFlow<Long?> =
        generation.flatMapLatest { repo.observeCurrentRunId() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val runProgress: StateFlow<RunProgress?> =
        currentRunId.flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeProgress(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val runEvents: StateFlow<List<EventEntity>> =
        currentRunId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeEvents(id, 200) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val runNextChannel: StateFlow<String?> =
        currentRunId.flatMapLatest { id -> if (id == null) flowOf(null) else repo.observeNextChannelName(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val runChannelLines: StateFlow<List<RunChannelLine>> =
        currentRunId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeChannelLines(id) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val workState: StateFlow<RunScheduler.WorkState> =
        scheduler.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, RunScheduler.WorkState.NONE)

    /** The run that is started and not ended, if any; another run cannot start until it is stopped. */
    val openRunId: StateFlow<Long?> =
        generation.flatMapLatest { repo.observeOpenRunId() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val deletedToday: StateFlow<Int> =
        generation.flatMapLatest { repo.observeDeletedSince(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** A message for the Run screen. */
    var runNotice by mutableStateOf<String?>(null)

    // ---- Fetch from Discord (0.3.0): the fetch's state, its progress rows and its events. -----------------------

    private val fetchScheduler = FetchScheduler(app)
    val fetchWork: StateFlow<RunScheduler.WorkState> =
        fetchScheduler.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, RunScheduler.WorkState.NONE)
    val fetchView: StateFlow<FetchView?> = graph.fetchState.state
    val fetchCounts: StateFlow<FetchCounts?> =
        generation.flatMapLatest { repo.observeFetchCounts() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val fetchEvents: StateFlow<List<EventEntity>> =
        generation.flatMapLatest { repo.observeFetchEvents(100) }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A message for the fetch screens (a refusal, a failure). */
    var fetchNotice by mutableStateOf<String?>(null)

    // ---- Reports (M4): read only while the Reports screen is on screen (WhileSubscribed), so a run's writes do not ----
    // ---- re-run these queries when nobody is looking. ------------------------------------------------------------

    private val whileShown = SharingStarted.WhileSubscribed(5_000)
    val statusCounts: StateFlow<List<StatusCount>?> =
        generation.flatMapLatest { repo.observeStatusCounts() }.stateIn(viewModelScope, whileShown, null)
    val keptCount: StateFlow<Int> =
        generation.flatMapLatest { repo.observeKeptCount() }.stateIn(viewModelScope, whileShown, 0)
    val leftoverCount: StateFlow<Int> =
        generation.flatMapLatest { repo.observeLeftoverCount() }.stateIn(viewModelScope, whileShown, 0)
    val reportRuns: StateFlow<List<RunReport>?> =
        generation.flatMapLatest { repo.observeRunReports() }.stateIn(viewModelScope, whileShown, null)
    val sourceCounts: StateFlow<List<SourceCount>?> =
        generation.flatMapLatest { repo.observeSourceCounts() }.stateIn(viewModelScope, whileShown, null)

    /** A run's per-channel lines, for an expanded row of the Reports screen. */
    fun observeRunChannelLines(runId: Long) = generation.flatMapLatest { repo.observeChannelLines(runId) }

    /** "Exported N rows", or the failure's class name. */
    var reportNotice by mutableStateOf<String?>(null)
    var exporting by mutableStateOf(false)
        private set

    /**
     * Writes the leftovers (channel_id,message_id and nothing else) to the file the user picked through the system's
     * file picker. Read and written off the main thread; a failure is reported by class name only.
     */
    fun exportLeftovers(uri: Uri) {
        if (wiping || exporting) return
        exporting = true
        reportNotice = null
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val pairs = repo.leftovers().map { it.channelId to it.messageId }
                    val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("the file could not be opened")
                    out.use { it.write(LeftoversCsv.toBytes(pairs)) }
                    pairs.size
                }
                reportNotice = "Exported $count rows"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportNotice = "Export failed: ${e.javaClass.simpleName}"
            } finally {
                exporting = false
            }
        }
    }

    // ---- Credentials (M3): the status only, never a value. ------------------------------------------------------

    /** Null until the first read; then None, Unreadable or Present (a capture time and an age). */
    var credentialState by mutableStateOf<CredentialState?>(null)
        private set
    var credentialNotice by mutableStateOf<String?>(null)
    var credentialsBusy by mutableStateOf(false)
        private set

    fun refreshCredentialStatus() {
        viewModelScope.launch { credentialState = withContext(Dispatchers.IO) { graph.credentialStore.status() } }
    }

    /** Parses a "Copy as cURL" text (or a pasted headers panel) and stores the five headers. The text is not kept. */
    fun importCurl(text: String) = storeHeaders {
        val parsed = CurlParser.parse(text)
        if (!parsed.complete) return@storeHeaders null to "Not found in the paste: ${parsed.missing.joinToString(", ")}. Copy a request to Discord's API as cURL from the browser's Network panel, or fill the fields by hand."
        parsed.toHeaderSet(System.currentTimeMillis())?.let { it to null }
            ?: (null to "A value is not usable (every field must be present and plain ASCII). Nothing was stored.")
    }

    /** The manual form: the five fields typed or pasted one by one. */
    fun importManual(authorization: String, userAgent: String, superProperties: String, locale: String, timezone: String) = storeHeaders {
        try {
            HeaderSet(authorization.trim(), userAgent.trim(), superProperties.trim(), locale.trim(), timezone.trim(), System.currentTimeMillis()) to null
        } catch (e: IllegalArgumentException) {
            null to (e.message ?: "invalid")
        }
    }

    private fun storeHeaders(build: () -> Pair<HeaderSet?, String?>) {
        if (credentialsBusy) return
        credentialsBusy = true
        viewModelScope.launch {
            try {
                val (headers, problem) = build()
                if (headers == null) {
                    credentialNotice = problem
                    return@launch
                }
                withContext(Dispatchers.IO) { graph.credentialStore.save(headers) }
                credentialNotice = "Stored: Authorization, User-Agent, X-Super-Properties, X-Discord-Locale, X-Discord-Timezone. Captured now."
                refreshCredentialStatus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                credentialNotice = "Could not store the credentials: ${e.javaClass.simpleName}. Nothing was kept."
            } finally {
                credentialsBusy = false
            }
        }
    }

    /**
     * Stores the set the sign-in page captured (B34 Form A), exactly as a cURL import stores it, then closes the
     * sign-in screen. The page was already ended; [clearFailed] names the clear's failed steps, if any. [onResult]
     * gets whether it was stored and the screen's status line; never a value. Refused while another store runs.
     */
    fun storeSignIn(headers: HeaderSet, clearFailed: List<String>, onResult: (Boolean, String) -> Unit) {
        val cleared = if (clearFailed.isEmpty()) null else signInClearNote(clearFailed)
        if (credentialsBusy) {
            onResult(false, listOfNotNull("Another capture is being stored; nothing was kept from this one.", cleared).joinToString(" "))
            return
        }
        credentialsBusy = true
        viewModelScope.launch {
            val problem = try {
                withContext(Dispatchers.IO) { graph.credentialStore.save(headers) }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Could not store the credentials: ${e.javaClass.simpleName}. Nothing was kept."
            } finally {
                credentialsBusy = false
            }
            val stored = "Signed in on the phone. Stored: Authorization, User-Agent, X-Super-Properties, X-Discord-Locale, X-Discord-Timezone. Captured just now."
            credentialNotice = when {
                problem == null && cleared == null -> "$stored Everything the sign-in page stored was deleted."
                problem == null -> "$stored $cleared"
                cleared == null -> "$problem The sign-in page's data was deleted."
                else -> "$problem $cleared"
            }
            refreshCredentialStatus()
            if (problem != null) {
                onResult(false, credentialNotice.orEmpty())
                return@launch
            }
            onResult(true, "Captured; closing.")
            delay(SIGN_IN_CLOSE_MS)
            if (route == Route.SignIn) back()
        }
    }

    /** Whether a sign-in page may load: false when what an earlier page left on disk could not be deleted (rule 19). */
    suspend fun signInStorageClean(): Boolean = graph.signInStorageClean()

    /**
     * Why "Sign in here" is off, or null. A fresh login and the page's own traffic must not run beside an active run
     * (CLAUDE.md rule 3), and nothing starts during a wipe.
     */
    fun signInBlockedReason(work: RunScheduler.WorkState, fetch: RunScheduler.WorkState = RunScheduler.WorkState.NONE): String? = when {
        wiping -> "Wiping"
        work != RunScheduler.WorkState.NONE -> "A run is active; pause or stop it first"
        fetch != RunScheduler.WorkState.NONE -> "A fetch is active; pause or stop it first"
        else -> null
    }

    /** The note for a clear that threw at these steps (names only). */
    fun signInClearNote(steps: List<String>): String =
        "Not every part of the sign-in page's data could be deleted (${steps.joinToString(", ")}). Wipe everything (Settings) removes it."

    /** The page was ended by Close, back or the app leaving the screen, and its clear threw: say so on the Credentials screen. */
    fun signInClearFailed(steps: List<String>) {
        credentialNotice = listOfNotNull(credentialNotice, signInClearNote(steps)).joinToString(" ")
    }

    fun wipeCredentials() {
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) { graph.credentialStore.wipe() }
            credentialNotice =
                if (removed) "Credentials wiped from this phone. Log out of the browser session too; that invalidates the token."
                else "Could not remove everything. Try again, or uninstall the app to remove its data."
            refreshCredentialStatus()
        }
    }

    /** Mirrors the process-wide import (Graph.imports), so a ViewModel made while an import runs shows it. */
    var importState by mutableStateOf(graph.imports.state.value)
        private set
    var browser by mutableStateOf<BrowserState?>(null)
        private set
    var filters by mutableStateOf<FilterState?>(null)
        private set
    val review = ReviewState()
    private var previewJob: Job? = null
    private var filterJob: Job? = null
    private var freezeJob: Job? = null

    /** True while Wipe everything runs; nothing else may start then. */
    var wiping by mutableStateOf(false)
        private set

    /** A message for the Settings screen (a failed wipe). */
    var settingsNotice by mutableStateOf<String?>(null)

    /** True while the run worker is running (mirrors [workState] into Compose state, so the Settings buttons follow it). */
    private var workerRunning by mutableStateOf(false)

    /** True while the fetch worker is running (mirrors [fetchWork], as [workerRunning] does for runs). */
    private var fetchRunning by mutableStateOf(false)

    /** Something is writing to the database: Wipe and Purge wait until it is done. */
    val busy: Boolean
        get() = wiping || importState is ImportState.Running || filters?.applying == true || review.freezing || workerRunning || exporting || fetchRunning

    /** The line under the disabled Purge and Wipe buttons; [runId] is the run to name if the worker is running. */
    fun busyNotice(runId: Long?): String? =
        if (wiping) "Wiping..." else WipeGate.busyNotice(importState is ImportState.Running, filters?.applying == true, review.freezing, workerRunning, runId, exporting, fetchRunning)

    // ---- Navigation ---------------------------------------------------------------------------------------------

    fun go(route: Route) {
        when (route) {
            is Route.Browser -> browser = BrowserState(route.channelId, this)
            is Route.Filters -> filters = FilterState(route.channelId).also { schedulePreview(it) }
            Route.Review -> startReview()
            Route.Credentials -> { credentialNotice = null; refreshCredentialStatus() }
            Route.Settings -> refreshCredentialStatus()
            Route.Reports -> reportNotice = null
            is Route.Frozen -> refreshCredentialStatus()
            Route.FetchNotice -> { fetchNotice = null; refreshCredentialStatus() }
            Route.Fetch -> fetchNotice = null
            else -> Unit
        }
        backStack.add(route)
    }

    fun back(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun home() {
        backStack.clear()
        backStack.add(Route.Channels)
    }

    // ---- Import -------------------------------------------------------------------------------------------------

    init {
        viewModelScope.launch { graph.imports.state.collect { importState = it } }
        viewModelScope.launch { workState.collect { workerRunning = it == RunScheduler.WorkState.RUNNING } }
        viewModelScope.launch { fetchWork.collect { fetchRunning = it == RunScheduler.WorkState.RUNNING } }
    }

    /**
     * Starts an import in the process-wide runner (Graph.imports). It runs to the end even if this screen or
     * ViewModel goes away; only Wipe everything stops it. A second import is refused while one runs.
     */
    fun import(uri: Uri) {
        if (wiping || graph.imports.isRunning) return
        val resolver = getApplication<Application>().contentResolver
        val repository = repo
        graph.settings.setImportUnfinished(true)
        graph.imports.start(
            work = { checkpoint, progress ->
                repository.import(
                    source = { resolver.openInputStream(uri) ?: throw IOException("the file could not be opened") },
                    checkCancelled = checkpoint,
                    progressCallback = progress,
                )
            },
            onCompleted = { graph.settings.setImportUnfinished(false) },
        )
    }

    fun resetImport() = graph.imports.reset()

    // ---- Channels and browser -----------------------------------------------------------------------------------

    fun observeSummary(id: Long) = generation.flatMapLatest { repo.observeSummary(id) }
    fun observeMessageCount(id: Long, query: String) = generation.flatMapLatest { repo.observeMessageCount(id, query) }

    fun newPager(channelId: Long, query: String?) = Pager(viewModelScope) { offset, limit -> repo.page(channelId, query, limit, offset) }

    fun setIncluded(id: Long, included: Boolean) = viewModelScope.launch { repo.setIncluded(id, included) }
    fun setAllIncluded(included: Boolean) = viewModelScope.launch { repo.setAllIncluded(included) }
    fun setMode(id: Long, mode: DmMode) = viewModelScope.launch { repo.setMode(id, mode) }

    fun toggle(row: MessageRow) = viewModelScope.launch {
        repo.toggleSelected(row)
        browser?.pager?.refresh()
    }

    fun setKeep(ids: List<Long>, keep: Boolean) = viewModelScope.launch {
        repo.setKeep(ids, keep)
        browser?.pager?.refresh()
    }

    fun setSelected(ids: List<Long>, selected: Boolean) = viewModelScope.launch {
        repo.setSelectedManually(ids, selected)
        browser?.pager?.refresh()
    }

    // ---- Filters ------------------------------------------------------------------------------------------------

    private fun scopeIds(state: FilterState): List<Long> =
        if (state.allDms || state.channelId == null) summaries.value.orEmpty().map { it.channelId } else listOf(state.channelId)

    /** Recounts the live preview shortly after the last change. */
    fun schedulePreview(state: FilterState) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(350)
            state.counting = true
            try {
                state.preview = withContext(Dispatchers.Default) { repo.previewFilter(scopeIds(state), state.filter()) }
            } finally {
                state.counting = false
            }
        }
    }

    fun applyFilter(state: FilterState) = runFilterAction(state) { repo.applyFilter(scopeIds(state), state.filter()) }
    fun clearFilterSelection(state: FilterState) = runFilterAction(state) { repo.clearFilterSelection(scopeIds(state)) }

    private fun runFilterAction(state: FilterState, action: suspend () -> Int) {
        if (state.applying || wiping) return
        state.applying = true
        filterJob = viewModelScope.launch {
            try {
                val changed = withContext(Dispatchers.Default) { action() }
                state.notice = "$changed messages changed."
            } finally {
                state.applying = false
            }
            schedulePreview(state)
        }
    }

    // ---- Review -------------------------------------------------------------------------------------------------

    private fun startReview() {
        review.plan = null
        review.pager = null
        review.notice = null
        recomputeReview()
    }

    private fun showPlan(plan: DeletePlan) {
        review.plan = plan
        review.pager = Pager(viewModelScope) { offset, limit -> repo.rowsAt(plan, offset, limit) }
    }

    private fun recomputeReview() {
        review.computing = true
        viewModelScope.launch {
            try {
                showPlan(withContext(Dispatchers.Default) { repo.computePlan() })
            } finally {
                review.computing = false
            }
        }
    }

    fun confirmFreeze() {
        val plan = review.plan ?: return
        if (review.freezing || wiping) return
        review.freezing = true
        freezeJob = viewModelScope.launch {
            try {
                when (val r = withContext(Dispatchers.Default) { repo.freeze(plan) }) {
                    is FreezeResult.Frozen -> {
                        backStack.removeAt(backStack.lastIndex)
                        backStack.add(Route.Frozen(r.runId, r.total, r.channels))
                    }
                    is FreezeResult.Changed -> {
                        showPlan(r.current)
                        review.notice = "Selections changed after this review was computed. Nothing was frozen; this is the new review."
                    }
                    FreezeResult.Empty -> review.notice = "Nothing to delete."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                review.notice = "Freezing failed, nothing was frozen: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            } finally {
                review.freezing = false
            }
        }
    }

    // ---- Settings -----------------------------------------------------------------------------------------------

    fun setPreset(p: PacingPreset) = graph.settings.update { it.copy(pacing = it.pacing.withPreset(p)) }
    fun setDailyCap(cap: Int) = graph.settings.update { it.copy(pacing = it.pacing.copy(dailyCap = cap)) }
    fun setQuietHours(q: QuietHours) = graph.settings.update { it.copy(pacing = it.pacing.copy(quietHours = q)) }
    fun setDryRun(on: Boolean) = graph.settings.update { it.copy(dryRun = on) }

    fun purgeContent() {
        if (busy) return
        viewModelScope.launch {
            try {
                repo.purgeContent()
                settingsNotice = "Local text purged."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsNotice = "Purge failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            }
        }
    }

    /**
     * Wipes the database, the settings and the stored credentials. In this order, each step before the next:
     *  0. an open run is marked Paused, so a refused wipe leaves it reading "Paused";
     *  1. the run worker is cancelled and WorkManager is asked until it reports nothing active (at most
     *     [WipeGate.WORKER_WAIT_MS]); if one is still active the wipe is refused and nothing is touched;
     *  2. the worker mutex (held by the worker for its whole body) and the send lock are taken by polling tryLock (same limit);
     *  3. the import is cancelled and joined, and a running filter apply or freeze is joined;
     *  4. the files are deleted; 5. WorkManager's finished-work records are pruned and the notifications cleared.
     * Nothing new starts while [wiping] is set.
     */
    fun wipeEverything() {
        if (wiping) return
        wiping = true
        viewModelScope.launch {
            var workerLocked = false
            var sendLocked = false
            try {
                // An open run (and an open fetch) reads "Paused" if the wipe is refused, not "interrupted".
                withContext(Dispatchers.IO) {
                    repo.openRunId()?.let { repo.pauseRun(it) }
                    graph.fetchState.paused(Executor.STOP_PAUSED_USER, null)
                }
                val refusal = cancelWorkerAndWait()
                if (refusal != null) {
                    settingsNotice = refusal
                    return@launch
                }
                // WorkManager calls a cancelled worker finished before its coroutine has stopped: wait for its real end.
                workerLocked = lockWithin(graph.workerMutex)
                if (!workerLocked) {
                    settingsNotice = WipeGate.STILL_WRITING
                    return@launch
                }
                sendLocked = lockWithin(graph.sendMutex)
                if (!sendLocked) {
                    settingsNotice = WipeGate.STILL_WRITING
                    return@launch
                }
                previewJob?.cancel()
                graph.imports.stopAndJoin()
                filterJob?.join()
                freezeJob?.join()
                withContext(Dispatchers.IO) { graph.wipeEverything() }
                generation.value += 1
                graph.imports.reset()
                browser = null
                filters = null
                settingsNotice = null
                reportNotice = null
                runNotice = null
                credentialNotice = null
                refreshCredentialStatus()
                // After the wipe, no record of a run (WorkManager's own) and no notification naming one stays behind.
                withContext(Dispatchers.IO) {
                    runCatching { scheduler.prune() }
                    runCatching { getApplication<Application>().getSystemService(NotificationManager::class.java).cancelAll() }
                }
                home()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsNotice = "Wipe failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}. Try again, or uninstall the app to remove its data."
            } finally {
                if (sendLocked) graph.sendMutex.unlock()
                if (workerLocked) graph.workerMutex.unlock()
                wiping = false
            }
        }
    }

    /**
     * Takes [mutex] by polling `tryLock()` until the wipe's deadline. Never a timeout around `lock()`: a timeout can
     * fire after the lock was taken and leave it held for good.
     */
    private suspend fun lockWithin(mutex: Mutex): Boolean {
        val start = System.currentTimeMillis()
        while (true) {
            if (mutex.tryLock()) return true
            if (WipeGate.decide(true, System.currentTimeMillis() - start) is WipeGate.Decision.Refuse) return false
            delay(250)
        }
    }

    /**
     * Cancels the run worker and the fetch worker and waits for WorkManager to report neither enqueued nor running; a
     * refusal text, or null when clear.
     */
    private suspend fun cancelWorkerAndWait(): String? {
        scheduler.cancel()
        fetchScheduler.cancel()
        val start = System.currentTimeMillis()
        while (true) {
            val active = scheduler.observeActive().first() != RunScheduler.WorkState.NONE ||
                fetchScheduler.observeActive().first() != RunScheduler.WorkState.NONE
            when (val d = WipeGate.decide(active, System.currentTimeMillis() - start)) {
                WipeGate.Decision.Ready -> return null
                WipeGate.Decision.WaitForWorker -> delay(250)
                is WipeGate.Decision.Refuse -> return d.reason
            }
        }
    }

    // ---- Runs (M2) ----------------------------------------------------------------------------------------------

    fun setOldestFirst(on: Boolean) = graph.settings.update { it.copy(oldestFirst = on) }

    /** What [StartRules] needs, read off the main thread. The header set is only tested for presence, never kept. */
    private class StartFacts(val openRunId: Long?, val startedAt: Long?, val options: RunOptionsEntity?, val hasHeaders: Boolean)

    private suspend fun startFacts(runId: Long) = withContext(Dispatchers.IO) {
        StartFacts(repo.openRunId(), repo.runSnapshot(runId)?.startedAt, repo.runOptions(runId), graph.credentials.load() != null)
    }

    private fun refusalText(r: StartDecision.Refuse): String = when (r.reason) {
        StartRefusal.OTHER_RUN_OPEN -> "Run #${r.openRunId} is still open. Stop it (or let it finish) before starting another."
        StartRefusal.HELD -> "Not before ${formatTime(r.notBefore ?: 0L)}."
        StartRefusal.NO_CREDENTIALS_START ->
            "No credentials stored, so a real run cannot start. Import them on the Credentials screen (Settings), or switch Dry run on. Nothing was started."
        StartRefusal.NO_CREDENTIALS_RESUME ->
            "No credentials stored, so this real run cannot continue. Import them on the Credentials screen (Settings). Nothing was started."
        StartRefusal.FETCH_ACTIVE -> "A fetch from Discord is active. Pause or stop it (Fetch screen) before a run starts. Nothing was started."
    }

    /** Whether the fetch worker is enqueued or running: no run starts beside it (CLAUDE.md rule 3). */
    private fun fetchActive(): Boolean = fetchWork.value != RunScheduler.WorkState.NONE

    /** Hands a run that has options to the worker with them. Nothing starts once a wipe has begun. */
    private fun continueRun(runId: Long, options: RunOptionsEntity?) {
        if (options == null) {
            runNotice = "This run was never started; start it from its frozen screen."
            return
        }
        if (wiping) return
        scheduler.start(runId, options.dryRun)
        runNotice = null
    }

    /**
     * Starts a frozen run as a dry run or a real one, per the Dry run toggle at this moment. The choice is written to
     * the run once; a later resume keeps it. The decision (an open run first, credentials for a real run, the hold
     * after an edge block) is [StartRules.decide], tested on the JVM.
     */
    fun startRun(runId: Long) {
        if (wiping) return
        val s = settings.value
        viewModelScope.launch {
            try {
                val f = startFacts(runId)
                // Once a run has options, its stored mode counts; the toggle only decides for a run with none yet.
                val dryRun = f.options?.dryRun ?: s.dryRun
                when (val d = StartRules.decide(runId, f.openRunId, f.startedAt, dryRun, f.hasHeaders, f.options?.resumeNotBefore, System.currentTimeMillis(), fetchActive())) {
                    is StartDecision.Refuse -> { runNotice = refusalText(d) }
                    StartDecision.Resume -> continueRun(runId, f.options)
                    StartDecision.Start -> {
                        val options = withContext(Dispatchers.IO) { repo.prepareRun(runId, s.dryRun, s.oldestFirst) }
                        if (wiping) return@launch
                        scheduler.start(runId, options.dryRun)
                        runNotice = null
                        backStack.removeAt(backStack.lastIndex)
                        backStack.add(Route.Run)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runNotice = "Could not start: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            }
        }
    }

    /** Resumes an interrupted, paused, halted or waiting run with the options it was started with. */
    fun resumeRun(runId: Long) {
        if (wiping) return
        viewModelScope.launch {
            try {
                val f = startFacts(runId)
                val options = f.options
                if (options == null) {
                    runNotice = "This run was never started; start it from its frozen screen."
                    return@launch
                }
                when (val d = StartRules.decide(runId, f.openRunId, f.startedAt, options.dryRun, f.hasHeaders, options.resumeNotBefore, System.currentTimeMillis(), fetchActive())) {
                    is StartDecision.Refuse -> { runNotice = refusalText(d) }
                    StartDecision.Resume, StartDecision.Start -> continueRun(runId, options)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runNotice = "Could not resume: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}"
            }
        }
    }

    /** Pause: the worker is cancelled after the request in flight; the run stays open. */
    fun pauseRun(runId: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.pauseRun(runId) }
        scheduler.cancel()
    }

    /** Stop: the run is ended now and the worker cancelled after the request in flight. */
    fun stopRun(runId: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.stopRun(runId) }
        scheduler.cancel()
    }

    /**
     * On every return to the foreground: a run that was waiting for quiet hours, the daily cap or the app itself
     * continues now if its time has come. A foreground service can only start while the app is in front.
     */
    fun onAppForeground() {
        if (wiping) return
        viewModelScope.launch {
            // Read the rows directly: on a cold open the observed flows have not emitted yet.
            val resume = withContext(Dispatchers.IO) {
                val id = repo.currentRunId() ?: return@withContext null
                val snap = repo.runSnapshot(id) ?: return@withContext null
                if (snap.endedAt != null || snap.stopReason?.startsWith("waiting:") != true) return@withContext null
                val options = repo.runOptions(id) ?: return@withContext null
                val hold = options.resumeNotBefore
                if (hold != null && hold > System.currentTimeMillis()) return@withContext null
                if (!options.dryRun && graph.credentials.load() == null) return@withContext null
                id to options.dryRun
            }
            if (resume == null) {
                // No run to continue: a fetch that was waiting may continue instead (never both; one worker at a time).
                resumeWaitingFetch()
                return@launch
            }
            if (wiping || fetchActive()) return@launch
            scheduler.start(resume.first, resume.second)
        }
    }

    // ---- Fetch from Discord (0.3.0) -----------------------------------------------------------------------------

    /** The Channels screen's "Fetch from Discord": the open fetch if there is one, else the notice that comes first. */
    fun openFetch() {
        val open = fetchView.value?.snapshot?.let { it.endedAt == null } == true
        go(if (open || fetchWork.value != RunScheduler.WorkState.NONE) Route.Fetch else Route.FetchNotice)
    }

    private fun fetchRefusalText(r: FetchDecision.Refuse): String = when (r.reason) {
        FetchRefusal.WIPING -> "Wiping; nothing was started."
        FetchRefusal.RUN_ACTIVE -> "A run is active. Pause or stop it (Run screen) before a fetch starts. Nothing was started."
        FetchRefusal.HELD -> "Not before ${formatTime(r.notBefore ?: 0L)}."
        FetchRefusal.NO_CREDENTIALS ->
            "No credentials stored, so a fetch cannot start. Import them on the Credentials screen (Settings). Nothing was started."
    }

    /** What StartRules.decideFetch needs, read off the main thread. The header set is only tested for presence. */
    private suspend fun fetchDecision(): FetchDecision {
        val hasHeaders = withContext(Dispatchers.IO) { graph.credentials.load() != null }
        val snap = fetchView.value?.snapshot
        val open = snap != null && snap.endedAt == null
        return StartRules.decideFetch(
            wiping, workState.value != RunScheduler.WorkState.NONE, hasHeaders, open, snap?.resumeNotBefore, System.currentTimeMillis(),
        )
    }

    /**
     * "Start fetch" on the notice: a new fetch (no progress rows; the account id and the DM list read again) unless one
     * is open, which continues instead. A fetch is not a run: the dry-run latch is not read or changed.
     */
    fun startFetch() {
        if (wiping) return
        viewModelScope.launch {
            try {
                when (val d = fetchDecision()) {
                    is FetchDecision.Refuse -> fetchNotice = fetchRefusalText(d)
                    FetchDecision.Resume, FetchDecision.Start -> {
                        if (d == FetchDecision.Start) {
                            withContext(Dispatchers.IO) {
                                repo.clearFetchProgress()
                                graph.fetchState.begin(System.currentTimeMillis())
                            }
                        }
                        if (wiping) return@launch
                        fetchScheduler.start()
                        fetchNotice = null
                        if (route == Route.FetchNotice) backStack.removeAt(backStack.lastIndex)
                        backStack.add(Route.Fetch)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fetchNotice = "Could not start: ${e.javaClass.simpleName}"
            }
        }
    }

    /** Resumes a paused, halted, waiting or interrupted fetch where its progress rows say. */
    fun resumeFetch() {
        if (wiping) return
        viewModelScope.launch {
            try {
                when (val d = fetchDecision()) {
                    is FetchDecision.Refuse -> fetchNotice = fetchRefusalText(d)
                    FetchDecision.Resume -> {
                        if (wiping) return@launch
                        fetchScheduler.start()
                        fetchNotice = null
                    }
                    FetchDecision.Start -> fetchNotice = "This fetch has ended; start a new one from the notice."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fetchNotice = "Could not resume: ${e.javaClass.simpleName}"
            }
        }
    }

    /** Pause: the worker is cancelled after the read in flight and its rows; the fetch stays open. */
    fun pauseFetch() = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.fetchState.paused(Executor.STOP_PAUSED_USER, null) }
        fetchScheduler.cancel()
    }

    /** Stop: the fetch ends now (what it found stays) and the worker is cancelled after the read in flight. */
    fun stopFetch() = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.fetchState.ended(System.currentTimeMillis(), Executor.STOP_STOPPED) }
        fetchScheduler.cancel()
    }

    /** A fetch waiting for quiet hours, the daily cap or the app continues on return to the foreground when its time has come. */
    private suspend fun resumeWaitingFetch() {
        val snap = graph.fetchState.snapshot() ?: return
        if (snap.endedAt != null || snap.stopReason?.startsWith("waiting:") != true) return
        val hold = snap.resumeNotBefore
        if (hold != null && hold > System.currentTimeMillis()) return
        if (withContext(Dispatchers.IO) { graph.credentials.load() } == null) return
        if (wiping || workState.value != RunScheduler.WorkState.NONE) return
        fetchScheduler.start()
    }

    /** The fetch's status line, in the run's words for halts (FetchSession.describe). */
    fun describeFetch(v: FetchView?): String =
        if (v == null) "No fetch yet." else FetchSession.describe(v.snapshot.stopReason, v.snapshot.endedAt, v.snapshot.startedAt)
}
