package dev.sortes.app.sources

import dev.sortes.app.alphaxiv.AlphaxivClient
import dev.sortes.app.arxiv.ArxivId
import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What syncing one folder did. */
data class SyncResult(
    val folderName: String?,
    val added: Int = 0,
    val alreadyPresent: Int = 0,
    /** Papers without an arXiv ID (e.g. private uploads), which can't be imported. */
    val skipped: Int = 0,
    /** Error messages of papers that failed to import, in folder order. */
    val failures: List<String> = emptyList(),
) {
    /** One line for the sources list, e.g. "2 added, 1 already in library, 1 failed (HTTP 503)". */
    fun summary(): String {
        val parts = buildList {
            if (added > 0) add("$added added")
            if (alreadyPresent > 0) add("$alreadyPresent already in library")
            if (skipped > 0) add("$skipped not on arXiv")
            if (failures.isNotEmpty()) add("${failures.size} failed (${failures.first()})")
        }
        return parts.joinToString(", ").ifEmpty { "Folder is empty" }
    }
}

/**
 * Adds every paper in an alphaXiv shared folder to the library through [importArxiv], which
 * must dedupe (see PaperImporter.importArxiv). Papers are imported one by one; a failing paper
 * doesn't stop the rest, and re-syncing retries it.
 */
class FolderSyncer(
    private val client: AlphaxivClient,
    private val paperDao: PaperDao,
    private val importArxiv: suspend (String) -> Paper,
    /** arXiv asks API clients to wait 3 s between requests. */
    private val fetchSpacingMillis: Long = 3_000,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** Throws [java.io.IOException] if the folder itself can't be read; then nothing is imported. */
    suspend fun sync(folderId: String): SyncResult {
        val folder = client.fetchFolder(folderId)
        var result = SyncResult(folder.name)
        var fetched = false
        for (universalId in folder.paperIds) {
            val arxivId = ArxivId.parse(universalId)
            if (arxivId == null) {
                result = result.copy(skipped = result.skipped + 1)
                continue
            }
            // Checked here, not just in the importer, to count it and skip the arXiv request.
            if (paperDao.findByPdfUrl(arxivId.pdfUrl) != null) {
                result = result.copy(alreadyPresent = result.alreadyPresent + 1)
                continue
            }
            if (fetched) pause(fetchSpacingMillis)
            fetched = true
            result = try {
                importArxiv(arxivId.id)
                result.copy(added = result.added + 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                result.copy(failures = result.failures + (e.message ?: e.toString()))
            }
        }
        return result
    }
}
