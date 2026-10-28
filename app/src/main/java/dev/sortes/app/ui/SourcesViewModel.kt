package dev.sortes.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sortes.app.data.FolderSource
import dev.sortes.app.sources.FolderSyncs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class SourcesViewModel(application: Application) : AndroidViewModel(application) {

    // Syncs outlive this ViewModel; it only forwards requests and observes state.
    private val folders = FolderSyncs.get(application)

    val sources: StateFlow<List<FolderSource>> = folders.sources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncing: StateFlow<Set<Long>> = folders.syncing

    fun addAndSync(folderId: String) {
        folders.addAndSync(folderId)
    }

    fun sync(id: Long) {
        folders.sync(id)
    }

    fun syncAll() {
        folders.syncAll()
    }

    fun remove(id: Long) {
        folders.remove(id)
    }
}
