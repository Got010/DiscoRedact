package app.discoredact.core.importer

import java.util.concurrent.CancellationException

/** Thrown at a checkpoint after [StopSignal.request]: the import was stopped on purpose (Wipe everything). */
class ImportStoppedException : CancellationException("import stopped")

/**
 * Stops an import only when asked explicitly. Thread interrupts, a finished screen or a cleared ViewModel do not
 * stop it: an import runs to completion unless Wipe everything requests a stop, so a package is never left half
 * imported by accident. The sink calls [checkpoint] before each batch write.
 */
class StopSignal {
    @Volatile
    private var stop = false

    fun request() {
        stop = true
    }

    fun clear() {
        stop = false
    }

    val requested: Boolean get() = stop

    fun checkpoint() {
        if (stop) throw ImportStoppedException()
    }
}
