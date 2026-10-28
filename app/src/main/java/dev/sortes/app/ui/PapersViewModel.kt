package dev.sortes.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sortes.app.arxiv.HttpArxivClient
import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperDatabase
import dev.sortes.app.data.PaperRepository
import dev.sortes.app.importing.PaperImporter
import dev.sortes.app.importing.ShareRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of the most recent import, shown on the papers list. */
sealed interface ImportStatus {
    data object Running : ImportStatus
    data class Done(val title: String) : ImportStatus
    data class Failed(val message: String) : ImportStatus
}

class PapersViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PaperRepository.from(application)

    private val importer = PaperImporter(
        dao = PaperDatabase.get(application).paperDao(),
        arxiv = HttpArxivClient(),
        libraryDir = PaperImporter.libraryDir(application),
    )

    val papers: StateFlow<List<Paper>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importStatus = MutableStateFlow<ImportStatus?>(null)
    val importStatus: StateFlow<ImportStatus?> = _importStatus.asStateFlow()

    init {
        viewModelScope.launch { repository.seedIfEmpty() }
    }

    fun importArxiv(text: String) = runImport { importer.importArxiv(text) }

    /** Starts copying right away: content URI read grants don't outlive the caller for long. */
    fun importPdf(uri: Uri) = runImport {
        val resolver = getApplication<Application>().contentResolver
        withContext(Dispatchers.IO) {
            importer.importPdf(displayName(uri)) { resolver.openInputStream(uri) }
        }
    }

    fun import(request: ShareRequest) = when (request) {
        is ShareRequest.Arxiv -> importArxiv(request.text)
        is ShareRequest.Pdf -> importPdf(Uri.parse(request.uri))
    }

    fun dismissImportStatus() {
        _importStatus.value = null
    }

    private fun runImport(block: suspend () -> Paper) {
        _importStatus.value = ImportStatus.Running
        viewModelScope.launch {
            _importStatus.value = try {
                ImportStatus.Done(block().title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportStatus.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
}
