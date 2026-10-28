package dev.sortes.app.sources

import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.FolderSourceDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Saved alphaXiv folders and their syncs. Syncs run in [scope], one at a time; a source that is
 * already syncing or queued isn't queued again. Failures never escape into [scope]: they are
 * recorded on the source or reported through [error].
 */
class FolderSources(
    private val scope: CoroutineScope,
    private val dao: FolderSourceDao,
    private val syncer: FolderSyncer,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val sources: Flow<List<FolderSource>> = dao.observeAll()

    private val _syncing = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids of sources syncing now or queued to. */
    val syncing: StateFlow<Set<Long>> = _syncing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)

    /** The last failure that couldn't be recorded on a source (e.g. a database error). */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _unseenFailure = MutableStateFlow(false)

    /**
     * Whether something has failed since [markFailuresSeen]: a sync that failed outright or
     * for some of its papers, or anything reported through [error]. Lets the paper list point
     * to the folder sources screen, where the failure shows.
     */
    val unseenFailure: StateFlow<Boolean> = _unseenFailure.asStateFlow()

    private val syncLock = Mutex()
    private val jobs = ConcurrentHashMap<Long, Job>()

    /** Saves [folderId] (a no-op if it's already saved) and syncs it. */
    fun addAndSync(folderId: String): Job = scope.launch {
        val id = reporting("Couldn't save source") {
            dao.insert(FolderSource(folderId = folderId)).takeIf { it != -1L }
                ?: dao.findByFolderId(folderId)?.id
        } ?: return@launch
        sync(id)?.join()
    }

    /** Returns null if [id] is already syncing or queued. */
    fun sync(id: Long): Job? {
        var queued = false
        // update() may rerun the lambda, so decide afresh each time.
        _syncing.update { current ->
            queued = id !in current
            if (queued) current + id else current
        }
        if (!queued) return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            syncLock.withLock { syncNow(id) }
        }
        // Not a finally block: a job cancelled before it is dispatched never runs its body.
        // In this order, so a sync queued right after can't lose its job entry.
        job.invokeOnCompletion {
            jobs.remove(id, job)
            _syncing.update { it - id }
        }
        jobs[id] = job
        job.start()
        return job
    }

    fun syncAll(): Job = scope.launch {
        val all = reporting("Couldn't load sources") { dao.getAll() } ?: return@launch
        all.mapNotNull { sync(it.id) }.forEach { it.join() }
    }

    /** Forgets the source, stopping its sync if one is running; papers it added stay. */
    fun remove(id: Long): Job = scope.launch {
        jobs[id]?.cancelAndJoin()
        reporting("Couldn't remove source") { dao.delete(id) }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Called when folder sources, and so any failures, are on screen. */
    fun markFailuresSeen() {
        _unseenFailure.value = false
    }

    private suspend fun syncNow(id: Long) {
        val source = reporting("Couldn't load source") { dao.findById(id) } ?: return // removed while queued
        val (name, summary) = try {
            val result = syncer.sync(source.folderId)
            if (result.failures.isNotEmpty()) _unseenFailure.value = true
            result.folderName to result.summary()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _unseenFailure.value = true
            null to "Sync failed: ${e.message ?: e}"
        }
        reporting("Couldn't save sync result") { dao.recordSync(id, name, clock(), summary) }
    }

    /** Runs [block], turning a failure into [error] and a null result. */
    private suspend fun <T> reporting(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _error.value = "$what: ${e.message ?: e}"
        _unseenFailure.value = true
        null
    }
}
