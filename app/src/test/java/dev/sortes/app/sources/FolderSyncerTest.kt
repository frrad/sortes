package dev.sortes.app.sources

import dev.sortes.app.alphaxiv.AlphaxivClient
import dev.sortes.app.alphaxiv.AlphaxivFolder
import dev.sortes.app.alphaxiv.AlphaxivFolderNotFoundException
import dev.sortes.app.arxiv.ArxivClient
import dev.sortes.app.arxiv.ArxivEntry
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.FakePaperDao
import dev.sortes.app.importing.PaperImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FolderSyncerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val folderId = "01a1838a-db89-71d0-956c-e8067fdfee2a"
    private val dao = FakePaperDao()

    /** IDs from the saved fixture of the real folder. */
    private var folder = AlphaxivFolder("Sortes", listOf("2311.08105", "2609.20807", "2602.03839", "2512.03442"))
    private var folderError: IOException? = null
    private val client = AlphaxivClient { id ->
        assertEquals(folderId, id)
        folderError?.let { throw it }
        folder
    }

    private val fetched = mutableListOf<String>()
    private val failingIds = mutableSetOf<String>()
    private val arxiv = ArxivClient { id ->
        fetched += id
        if (id in failingIds) throw IOException("arXiv lookup failed: HTTP 503")
        ArxivEntry(title = "Paper $id", authors = listOf("A"), abstract = "", year = 2024)
    }

    // The real importer, so dedupe is the one the app uses.
    private val importer by lazy { PaperImporter(dao, arxiv, File(tmp.root, "papers")) }
    private val pauses = mutableListOf<Long>()
    private var importArxiv: suspend (String) -> Paper = { importer.importArxiv(it) }
    private val syncer = FolderSyncer(client, dao, { importArxiv(it) }, fetchSpacingMillis = 3_000, pause = { pauses += it })

    private fun sync() = runBlocking { syncer.sync(folderId) }

    private fun pdfUrls() = dao.papers.value.map { it.pdfUrl }

    private fun existing(id: String) = runBlocking {
        dao.insert(Paper(title = "Mine", authors = emptyList(), abstract = "", year = null, venue = null,
            pdfUrl = "https://arxiv.org/pdf/$id"))
    }

    @Test
    fun importsEveryPaperInFolder() {
        val result = sync()

        assertEquals(SyncResult("Sortes", added = 4), result)
        assertEquals(folder.paperIds, fetched)
        assertEquals(folder.paperIds.map { "https://arxiv.org/pdf/$it" }, pdfUrls())
        assertEquals("Paper 2311.08105", dao.papers.value.first().title)
    }

    @Test
    fun papersAlreadyInLibraryAreNotDuplicatedOrRefetched() {
        existing("2609.20807")

        val result = sync()

        assertEquals(SyncResult("Sortes", added = 3, alreadyPresent = 1), result)
        assertEquals(listOf("2311.08105", "2602.03839", "2512.03442"), fetched)
        assertEquals(4, dao.papers.value.size)
        assertEquals("Mine", dao.papers.value.single { it.pdfUrl == "https://arxiv.org/pdf/2609.20807" }.title)
    }

    @Test
    fun resyncIsIdempotent() {
        sync()
        fetched.clear()

        val result = sync()

        assertEquals(SyncResult("Sortes", alreadyPresent = 4), result)
        assertEquals(emptyList<String>(), fetched)
        assertEquals(4, dao.papers.value.size)
    }

    @Test
    fun versionedIdsDedupeAgainstVersionlessPaper() {
        existing("2311.08105")
        folder = AlphaxivFolder("F", listOf("2311.08105v3", "arXiv:2311.08105"))

        assertEquals(SyncResult("F", alreadyPresent = 2), sync())
        assertEquals(1, dao.papers.value.size)
    }

    @Test
    fun oldStyleIdsAreImported() {
        folder = AlphaxivFolder("F", listOf("hep-th/9901001"))

        assertEquals(SyncResult("F", added = 1), sync())
        assertEquals(listOf("https://arxiv.org/pdf/hep-th/9901001"), pdfUrls())
    }

    @Test
    fun nonArxivPapersAreSkipped() {
        folder = AlphaxivFolder("F", listOf("019a0000-0000-7000-8000-000000000000", "2311.08105", "not an id"))

        assertEquals(SyncResult("F", added = 1, skipped = 2), sync())
        assertEquals(listOf("2311.08105"), fetched)
    }

    @Test
    fun oneFailingPaperDoesNotStopTheRest() {
        failingIds += "2609.20807"

        val result = sync()

        assertEquals(SyncResult("Sortes", added = 3, failures = listOf("arXiv lookup failed: HTTP 503")), result)
        assertEquals(folder.paperIds, fetched)
        assertEquals(3, dao.papers.value.size)
    }

    @Test
    fun resyncRetriesFailedPapers() {
        failingIds += "2609.20807"
        sync()
        failingIds.clear()
        fetched.clear()

        val result = sync()

        assertEquals(SyncResult("Sortes", added = 1, alreadyPresent = 3), result)
        assertEquals(listOf("2609.20807"), fetched)
        assertEquals(4, dao.papers.value.size)
    }

    @Test
    fun nonIoFailuresAreCountedToo() {
        dao.failInserts = true

        val result = sync()

        assertEquals(4, result.failures.size)
        assertEquals(0, result.added)
    }

    @Test
    fun folderFetchFailureImportsNothing() {
        folderError = AlphaxivFolderNotFoundException(folderId)

        assertThrows(AlphaxivFolderNotFoundException::class.java) { sync() }
        assertEquals(emptyList<String>(), fetched)
        assertTrue(dao.papers.value.isEmpty())
    }

    @Test
    fun cancellationIsNotSwallowedAsAFailure() {
        importArxiv = { throw CancellationException("stop") }

        assertThrows(CancellationException::class.java) { sync() }
    }

    @Test
    fun arxivRequestsAreSpacedOut() {
        sync()

        // Four fetches, three gaps.
        assertEquals(listOf(3_000L, 3_000L, 3_000L), pauses)
    }

    @Test
    fun noPauseForPapersAlreadyPresent() {
        existing("2311.08105")
        existing("2609.20807")
        existing("2602.03839")

        sync()

        assertEquals(listOf("2512.03442"), fetched)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun emptyFolder() {
        folder = AlphaxivFolder(null, emptyList())

        assertEquals(SyncResult(null), sync())
    }

    @Test
    fun summaries() {
        assertEquals("Folder is empty", SyncResult("F").summary())
        assertEquals("4 added", SyncResult("F", added = 4).summary())
        assertEquals(
            "2 added, 1 already in library, 1 not on arXiv, 2 failed (HTTP 503)",
            SyncResult("F", added = 2, alreadyPresent = 1, skipped = 1, failures = listOf("HTTP 503", "other")).summary(),
        )
        assertEquals("3 already in library", SyncResult("F", alreadyPresent = 3).summary())
    }
}
