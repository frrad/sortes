package dev.sortes.app.importing

import dev.sortes.app.data.Paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    fun launch(block: suspend () -> Paper): Job {
        synchronized(lock) {
            running++
            _status.value = ImportStatus.Running(running)
        }
        var result: ImportStatus? = null
        val job = scope.launch {
            result = try {
                ImportStatus.Done(block().title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportStatus.Failed(e.message ?: e.toString())
            }
        }
        // Not a finally in the body: a job cancelled before it starts never runs its body,
        // but always completes.
        job.invokeOnCompletion {
            synchronized(lock) {
                running--
                result?.let { lastResult = it }
                _status.value = if (running > 0) ImportStatus.Running(running) else lastResult
            }
        }
        return job
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
