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
import app.discoredact.core.importer.ImportProgress
import app.discoredact.core.importer.ImportReport
import app.discoredact.core.model.DmMode
import app.discoredact.core.pacing.PacingPreset
import app.discoredact.core.pacing.QuietHours
import app.discoredact.core.selection.DeletePlan
import app.discoredact.core.selection.MessageFilter
import app.discoredact.data.AppSettings
import app.discoredact.data.ChannelSummary
import app.discoredact.data.FilterPreview
import app.discoredact.data.FreezeResult
import app.discoredact.data.MessageRow
import app.discoredact.data.Repository
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
}

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val progress: ImportProgress?) : ImportState
    data class Done(val report: ImportReport) : ImportState
    data class Failed(val message: String) : ImportState
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

    var importState by mutableStateOf<ImportState>(ImportState.Idle)
        private set
    var browser by mutableStateOf<BrowserState?>(null)
        private set
    var filters by mutableStateOf<FilterState?>(null)
        private set
    val review = ReviewState()
    private var previewJob: Job? = null

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

    fun import(uri: Uri) {
        if (importState is ImportState.Running) return
        importState = ImportState.Running(null)
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            importState = try {
                val report = withContext(Dispatchers.IO) {
                    repo.import({ resolver.openInputStream(uri) ?: throw IOException("the file could not be opened") }) { p ->
                        viewModelScope.launch(Dispatchers.Main) { if (importState is ImportState.Running) importState = ImportState.Running(p) }
                    }
                }
                ImportState.Done(report)
            } catch (e: Exception) {
                ImportState.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
        }
    }

    fun resetImport() {
        if (importState !is ImportState.Running) importState = ImportState.Idle
    }

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
        if (state.applying) return
        state.applying = true
        viewModelScope.launch {
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
        if (review.freezing) return
        review.freezing = true
        viewModelScope.launch {
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

    fun purgeContent() = viewModelScope.launch { repo.purgeContent() }

    fun wipeEverything() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.wipeEverything() }
            generation.value += 1
            importState = ImportState.Idle
            browser = null
            filters = null
            home()
        }
    }
}
