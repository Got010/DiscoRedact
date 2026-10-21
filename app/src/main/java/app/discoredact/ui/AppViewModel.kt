package app.discoredact.ui

import android.app.Application
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
import app.discoredact.core.executor.Executor
import app.discoredact.data.AppSettings
import app.discoredact.data.ChannelSummary
import app.discoredact.data.EventEntity
import app.discoredact.data.FilterPreview
import app.discoredact.data.FreezeResult
import app.discoredact.data.ImportState
import app.discoredact.data.MessageRow
import app.discoredact.data.Repository
import app.discoredact.data.RunChannelLine
import app.discoredact.data.RunProgress
import app.discoredact.work.RunScheduler
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

sealed interface Route {
    data object Channels : Route
    data object Import : Route
    data class Browser(val channelId: Long) : Route
    data class Filters(val channelId: Long?) : Route
    data object Review : Route
    data class Frozen(val runId: Long, val total: Int, val channels: Int) : Route
    data object Settings : Route
    data object Run : Route
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

    /** Something is writing to the database: Wipe and Purge wait until it is done. */
    val busy: Boolean
        get() = wiping || importState is ImportState.Running || filters?.applying == true || review.freezing

    // ---- Navigation ---------------------------------------------------------------------------------------------

    fun go(route: Route) {
        when (route) {
            is Route.Browser -> browser = BrowserState(route.channelId, this)
            is Route.Filters -> filters = FilterState(route.channelId).also { schedulePreview(it) }
            Route.Review -> startReview()
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
     * Deletes the database and settings. The import is cancelled and joined first, and a running filter apply or
     * freeze is joined, so no write can overlap the delete; nothing new starts while [wiping] is set.
     */
    fun wipeEverything() {
        if (wiping) return
        wiping = true
        viewModelScope.launch {
            try {
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
                home()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                settingsNotice = "Wipe failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}. Try again, or uninstall the app to remove its data."
            } finally {
                wiping = false
            }
        }
    }

    // ---- Runs (M2) ----------------------------------------------------------------------------------------------

    fun setOldestFirst(on: Boolean) = graph.settings.update { it.copy(oldestFirst = on) }

    /**
     * Starts a frozen run as a dry run or a real one, per the Dry run toggle at this moment. The choice is written to
     * the run once; a later resume keeps it. A real run with no stored credentials pauses at once (milestone 3).
     */
    fun startRun(runId: Long) {
        if (wiping) return
        val s = settings.value
        viewModelScope.launch {
            try {
                // An interrupted, paused, halted or waiting run comes first (brief decision 1): Stop it to start another.
                val open = withContext(Dispatchers.IO) { repo.openRunId() }
                if (open != null && open != runId) {
                    runNotice = "Run #$open is still open. Stop it (or let it finish) before starting another."
                    return@launch
                }
                // A real run needs a complete header set; without one nothing starts (brief decision 5).
                if (!s.dryRun && graph.credentials.load() == null) {
                    runNotice = "No credentials stored, so a real run cannot start (milestone 3). Switch Dry run on, or import credentials first. Nothing was started."
                    return@launch
                }
                val options = withContext(Dispatchers.IO) { repo.prepareRun(runId, s.dryRun, s.oldestFirst) }
                scheduler.start(runId, options.dryRun)
                runNotice = null
                backStack.removeAt(backStack.lastIndex)
                backStack.add(Route.Run)
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
            val options = withContext(Dispatchers.IO) { repo.runOptions(runId) }
            if (options == null) {
                runNotice = "This run was never started; start it from its frozen screen."
                return@launch
            }
            val hold = options.resumeNotBefore
            if (hold != null && hold > System.currentTimeMillis()) {
                runNotice = "Not before ${formatTime(hold)}."
                return@launch
            }
            if (!options.dryRun && graph.credentials.load() == null) {
                runNotice = "No credentials stored, so this real run cannot continue (milestone 3). Nothing was started."
                return@launch
            }
            scheduler.start(runId, options.dryRun)
            runNotice = null
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
            } ?: return@launch
            scheduler.start(resume.first, resume.second)
        }
    }
}
