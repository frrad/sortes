package dev.sortes.app.importing

import dev.sortes.app.data.Paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the papers list shows about imports. */
sealed interface ImportStatus {
    /** [count] imports are still in flight. */
    data class Running(val count: Int) : ImportStatus

    data class Done(val title: String) : ImportStatus

    data class Failed(val message: String) : ImportStatus
}

/**
 * Runs imports in [scope] and summarizes them as one [status]: [ImportStatus.Running] while any
 * import is in flight, then the result of the one that finished last.
 */
class ImportTracker(private val scope: CoroutineScope) {

    private val _status = MutableStateFlow<ImportStatus?>(null)
    val status: StateFlow<ImportStatus?> = _status.asStateFlow()

    private val lock = Any()
    private var running = 0
    private var lastResult: ImportStatus? = null

    @OptIn(ExperimentalCoroutinesApi::class) // CoroutineStart.ATOMIC
    fun launch(block: suspend () -> Paper): Job {
        synchronized(lock) {
            running++
            _status.value = ImportStatus.Running(running)
        }
        // Atomic, so the body runs even if the job is cancelled before it's dispatched, and the
        // count is settled in a finally block before the job completes. It was settled in a
        // completion handler, but join() on a job that has completed returns without waiting
        // for its handlers, so an import just joined could still show as running.
        return scope.launch(start = CoroutineStart.ATOMIC) {
            var result: ImportStatus? = null
            try {
                ensureActive() // don't start an import cancelled before it was dispatched
                result =
                    try {
                        ImportStatus.Done(block().title)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ImportStatus.Failed(e.message ?: e.toString())
                    }
            } finally {
                synchronized(lock) {
                    running--
                    result?.let { lastResult = it }
                    _status.value = if (running > 0) ImportStatus.Running(running) else lastResult
                }
            }
        }
    }

    /** Hides a finished result; has no effect while imports are running. */
    fun dismiss() {
        synchronized(lock) {
            if (running > 0) return
            lastResult = null
            _status.value = null
        }
    }
}
