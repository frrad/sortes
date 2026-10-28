package dev.sortes.app.sources

import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.FolderSourceDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** In-memory [FolderSourceDao] for JVM tests, with the same unique-folderId rule as the real table. */
class FakeFolderSourceDao : FolderSourceDao {
    val sources = MutableStateFlow<List<FolderSource>>(emptyList())
    private var nextId = 1L

    override fun observeAll(): Flow<List<FolderSource>> = sources

    override suspend fun getAll(): List<FolderSource> = sources.value

    override suspend fun findById(id: Long): FolderSource? = sources.value.find { it.id == id }

    override suspend fun findByFolderId(folderId: String): FolderSource? =
        sources.value.find { it.folderId == folderId }

    override suspend fun insert(source: FolderSource): Long = synchronized(this) {
        if (sources.value.any { it.folderId == source.folderId }) return -1
        val id = nextId++
        sources.update { it + source.copy(id = id) }
        id
    }

    override suspend fun recordSync(id: Long, name: String?, at: Long, result: String) {
        sources.update { list ->
            list.map { if (it.id == id) it.copy(name = name ?: it.name, lastSyncedAt = at, lastResult = result) else it }
        }
    }

    override suspend fun delete(id: Long) {
        sources.update { list -> list.filterNot { it.id == id } }
    }
}
