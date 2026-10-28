package dev.sortes.app.sources

import dev.sortes.app.alphaxiv.AlphaxivClient
import dev.sortes.app.arxiv.ArxivId
import dev.sortes.app.importing.ArxivImport
import kotlinx.coroutines.CancellationException

/** What syncing one folder did. */
data class SyncResult(
    val folderName: String?,
    val added: Int = 0,
    val alreadyPresent: Int = 0,
    /** Papers without an arXiv ID (e.g. private uploads), which can't be imported. */
    val skipped: Int = 0,
    /** Error messages of papers that failed to import, in folder order. */
    val failures: List<String> = emptyList(),
    /** False if the folder was too large to read in full; only its first pages were synced. */
    val complete: Boolean = true,
) {
    /** One line for the sources list, e.g. "2 added, 1 already in library, 1 failed (HTTP 503)". */
    fun summary(): String {
        val parts = buildList {
            if (added > 0) add("$added added")
            if (alreadyPresent > 0) add("$alreadyPresent already in library")
            if (skipped > 0) add("$skipped not on arXiv")
            if (failures.isNotEmpty()) add("${failures.size} failed (${failures.first()})")
        }
        val counts = parts.joinToString(", ").ifEmpty { "Folder is empty" }
        return if (complete) counts else "Partial, folder too large: $counts"
    }
}

/**
 * Adds every paper in an alphaXiv shared folder to the library through [importArxiv], which
 * must dedupe and say whether it inserted (see PaperImporter.importArxiv). Papers are imported
 * one by one; a failing paper doesn't stop the rest, and re-syncing retries it.
 */
class FolderSyncer(
    private val client: AlphaxivClient,
    private val importArxiv: suspend (ArxivId) -> ArxivImport,
) {
    /** Throws [java.io.IOException] if the folder itself can't be read; then nothing is imported. */
    suspend fun sync(folderId: String): SyncResult {
        val folder = client.fetchFolder(folderId)
        var result = SyncResult(folder.name, complete = folder.complete)
        for (universalId in folder.paperIds) {
            val arxivId = ArxivId.parse(universalId)
            if (arxivId == null) {
                result = result.copy(skipped = result.skipped + 1)
                continue
            }
            result = try {
                if (importArxiv(arxivId).inserted) {
                    result.copy(added = result.added + 1)
                } else {
                    result.copy(alreadyPresent = result.alreadyPresent + 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.copy(failures = result.failures + (e.message ?: e.toString()))
            }
        }
        return result
    }
}
