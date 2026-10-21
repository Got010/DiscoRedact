package app.discoredact.data

import app.discoredact.core.importer.ImportProgress
import app.discoredact.core.importer.ImportReport
import app.discoredact.core.importer.ImportStoppedException
import app.discoredact.core.importer.StopSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(val progress: ImportProgress?) : ImportState
    data class Done(val report: ImportReport) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * The one import that may run in this process. It lives in Graph, not in a ViewModel: an import outlives the screen
 * that started it, so every ViewModel (a reopened app gets a new one) observes the same [state], a second import is
 * refused while one runs, and Wipe everything stops and joins this import through the shared [StopSignal].
 *
 * [scope] must not be tied to a screen; the work is blocking and stops only at a checkpoint after [stopAndJoin].
 */
class ImportRunner(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private val stop = StopSignal()
    private var job: Job? = null

    val isRunning: Boolean get() = _state.value is ImportState.Running

    /**
     * Starts [work] unless an import is already running (then returns false). [work] gets the stop checkpoint to call
     * before each batch and a progress callback. [onCompleted] runs only when the import finished normally.
     */
    @Synchronized
    fun start(
        work: (checkpoint: () -> Unit, progress: (ImportProgress) -> Unit) -> ImportReport,
        onCompleted: () -> Unit = {},
    ): Boolean {
        if (isRunning) return false
        stop.clear()
        _state.value = ImportState.Running(null)
        job = scope.launch {
            _state.value = try {
                val report = work(stop::checkpoint) { p -> _state.update { if (it is ImportState.Running) ImportState.Running(p) else it } }
                onCompleted()
                ImportState.Done(report)
            } catch (_: ImportStoppedException) {
                ImportState.Idle
            } catch (e: Exception) {
                ImportState.Failed(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
        }
        return true
    }

    /** Asks a running import to stop at its next batch and waits until it has. A no-op when none runs. */
    suspend fun stopAndJoin() {
        val running = synchronized(this) {
            if (isRunning) stop.request()
            job
        }
        running?.join()
        stop.clear()
    }

    /** Back to Idle after a finished or failed import has been shown; never while one runs. */
    @Synchronized
    fun reset() {
        if (!isRunning) _state.value = ImportState.Idle
    }
}
