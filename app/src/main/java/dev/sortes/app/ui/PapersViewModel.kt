package dev.sortes.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperRepository
import dev.sortes.app.importing.ImportStatus
import dev.sortes.app.importing.PaperImports
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PapersViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PaperRepository.from(application)

    // Imports outlive this ViewModel; it only forwards requests and observes their status.
    private val imports = PaperImports.get(application)

    val papers: StateFlow<List<Paper>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val importStatus: StateFlow<ImportStatus?> = imports.status

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
    }

    fun importArxiv(text: String) = imports.importArxiv(text)

    fun importPdf(uri: Uri) = imports.importPdf(uri)

    fun dismissImportStatus() = imports.dismissStatus()
}
