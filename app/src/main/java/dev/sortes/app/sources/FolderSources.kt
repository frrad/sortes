package dev.sortes.app.sources

import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.FolderSourceDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Saved alphaXiv folders and their syncs. Syncs run in [scope], one at a time so arXiv requests
 * stay spaced out; a source that is already syncing or queued isn't queued again.
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

    private val syncLock = Mutex()

    /** Saves [folderId] (a no-op if it's already saved) and syncs it. */
    fun addAndSync(folderId: String): Job = scope.launch {
        val id = dao.insert(FolderSource(folderId = folderId)).takeIf { it != -1L }
            ?: dao.findByFolderId(folderId)?.id
            ?: return@launch
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
        return scope.launch {
            try {
                syncLock.withLock { syncNow(id) }
            } finally {
                _syncing.update { it - id }
            }
        }
    }

    fun syncAll(): Job = scope.launch {
        dao.getAll().mapNotNull { sync(it.id) }.forEach { it.join() }
    }

    /** Forgets the source; papers it added stay in the library. */
    fun remove(id: Long): Job = scope.launch { dao.delete(id) }

    private suspend fun syncNow(id: Long) {
        val source = dao.findById(id) ?: return // removed while queued
        val (name, summary) = try {
            syncer.sync(source.folderId).let { it.folderName to it.summary() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null to "Sync failed: ${e.message ?: e}"
        }
        dao.recordSync(id, name, clock(), summary)
    }
}
