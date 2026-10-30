package dev.sortes.app.sources

import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.FolderSourceDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Guards [jobs] and [_syncing] together, so each id's entry in both belongs to one job. */
    private val stateLock = Any()
    private val jobs = HashMap<Long, Job>()

    /** Saves [folderId] (a no-op if it's already saved) and syncs it. */
    fun addAndSync(folderId: String): Job = scope.launch {
        val id = reporting("Couldn't save source") {
            dao.insert(FolderSource(folderId = folderId)).takeIf { it != -1L }
                ?: dao.findByFolderId(folderId)?.id
        } ?: return@launch
        sync(id)?.join()
    }

    /** Returns null if [id] is already syncing or queued. */
    @OptIn(ExperimentalCoroutinesApi::class) // CoroutineStart.ATOMIC
    fun sync(id: Long): Job? {
        synchronized(stateLock) {
            if (id in _syncing.value) return null
            _syncing.value = _syncing.value + id
            // Atomic, so the body runs even if the job is cancelled before it's dispatched, and
            // the state is cleared in a finally block before the job completes: join() on a job
            // that has completed returns without waiting for completion handlers, so clearing it
            // in one let a sync just joined still show as syncing, and a resync be ignored.
            val job = scope.launch(start = CoroutineStart.ATOMIC) {
                try {
                    ensureActive() // withLock doesn't check, if the lock is free
                    syncLock.withLock { syncNow(id) }
                } finally {
                    // Waits for stateLock, so on another thread it runs after the job is registered.
                    synchronized(stateLock) {
                        jobs.remove(id, coroutineContext.job)
                        _syncing.value = _syncing.value - id
                    }
                }
            }
            // Unless an immediate dispatcher already ran the body to the end, on this thread.
            if (id in _syncing.value) jobs[id] = job
            return job
        }
    }

    fun syncAll(): Job = scope.launch {
        val all = reporting("Couldn't load sources") { dao.getAll() } ?: return@launch
        all.mapNotNull { sync(it.id) }.forEach { it.join() }
    }

    /** Forgets the source, stopping its sync if one is running; papers it added stay. */
    fun remove(id: Long): Job = scope.launch {
        synchronized(stateLock) { jobs[id] }?.cancelAndJoin()
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
