package dev.sortes.app.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.sortes.app.arxiv.HttpArxivClient
import dev.sortes.app.arxiv.RateLimitedArxivClient
import dev.sortes.app.data.PaperDatabase
import dev.sortes.app.sources.FolderSyncs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide entry point for imports. Imports run in an application scope, so they finish even if
 * the screen that started them goes away (e.g. Back right after sharing).
 */
class PaperImports private constructor(context: Context) {

    private val appContext = context.applicationContext

    internal val importer =
        PaperImporter(
            dao = PaperDatabase.get(appContext).paperDao(),
            // Shared by every arXiv request in the process (add dialog, shares, folder syncs).
            arxiv = RateLimitedArxivClient(HttpArxivClient()),
            libraryDir = PaperImporter.libraryDir(appContext),
        )

    private val tracker = ImportTracker(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    val status: StateFlow<ImportStatus?>
        get() = tracker.status

    fun importArxiv(text: String) {
        tracker.launch { importer.importArxiv(text) }
    }

    /** Copies right away on the IO dispatcher: content URI read grants are short-lived. */
    fun importPdf(uri: Uri) {
        tracker.launch {
            val resolver = appContext.contentResolver
            importer.importPdf(displayName(uri)) { resolver.openInputStream(uri) }
        }
    }

    fun import(request: ShareRequest) {
        when (request) {
            is ShareRequest.Arxiv -> importArxiv(request.text)
            is ShareRequest.Pdf -> importPdf(Uri.parse(request.uri))
            is ShareRequest.AlphaxivFolder ->
                FolderSyncs.get(appContext).addAndSync(request.folderId)
        }
    }

    fun dismissStatus() = tracker.dismiss()

    private fun displayName(uri: Uri): String? =
        try {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')

    companion object {
        @Volatile private var instance: PaperImports? = null

        fun get(context: Context): PaperImports =
            instance
                ?: synchronized(this) {
                    instance ?: PaperImports(context).also { instance = it }
                }
    }
}
