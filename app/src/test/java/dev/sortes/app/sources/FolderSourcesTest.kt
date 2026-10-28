package dev.sortes.app.sources

import dev.sortes.app.alphaxiv.AlphaxivClient
import dev.sortes.app.alphaxiv.AlphaxivFolder
import dev.sortes.app.alphaxiv.AlphaxivFolderNotFoundException
import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.FakePaperDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class FolderSourcesTest {

    private val folderA = "01a1838a-db89-71d0-956c-e8067fdfee2a"
    private val folderB = "01a1838a-db89-71d0-956c-e8067fdfee2b"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sourceDao = FakeFolderSourceDao()
    private val paperDao = FakePaperDao()

    private val folders = mutableMapOf(
        folderA to AlphaxivFolder("Sortes", listOf("2311.08105", "2609.20807")),
        folderB to AlphaxivFolder("Other", listOf("2311.08105", "2512.03442")),
    )

    /** When set, folder fetches wait on it, to observe a sync in flight. */
    private var gate: CompletableDeferred<Unit>? = null
    private val running = AtomicInteger()
    private val maxRunning = AtomicInteger()
    private val client = AlphaxivClient { id ->
        maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            gate?.await()
            folders[id] ?: throw AlphaxivFolderNotFoundException(id)
        } finally {
            running.decrementAndGet()
        }
    }

    private val syncer = FolderSyncer(client, paperDao, importArxiv = { id ->
        val paper = Paper(title = id, authors = emptyList(), abstract = "", year = null, venue = null,
            pdfUrl = "https://arxiv.org/pdf/$id")
        paper.copy(id = paperDao.insert(paper))
    }, pause = {})

    private val sources = FolderSources(scope, sourceDao, syncer, clock = { 1_000L })

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun source(folderId: String) = sourceDao.sources.value.single { it.folderId == folderId }

    @Test
    fun addAndSyncSavesSourceAndRecordsResult() = runBlocking {
        sources.addAndSync(folderA).join()

        assertEquals(
            FolderSource(id = 1, folderId = folderA, name = "Sortes", lastSyncedAt = 1_000L, lastResult = "2 added"),
            source(folderA),
        )
        assertEquals(2, paperDao.papers.value.size)
    }

    @Test
    fun addingSameFolderTwiceKeepsOneSourceAndResyncs() = runBlocking {
        sources.addAndSync(folderA).join()
        sources.addAndSync(folderA).join()

        assertEquals(1, sourceDao.sources.value.size)
        assertEquals("2 already in library", source(folderA).lastResult)
        assertEquals(2, paperDao.papers.value.size)
    }

    @Test
    fun foldersSharingAPaperDoNotDuplicateIt() = runBlocking {
        sources.addAndSync(folderA).join()
        sources.addAndSync(folderB).join()

        assertEquals("1 added, 1 already in library", source(folderB).lastResult)
        assertEquals(3, paperDao.papers.value.size)
    }

    @Test
    fun failedSyncIsRecordedAndKeepsPreviousName() = runBlocking {
        sources.addAndSync(folderA).join()
        folders.remove(folderA)

        sources.sync(source(folderA).id)!!.join()

        val source = source(folderA)
        assertEquals("Sortes", source.name)
        assertEquals("Sync failed: alphaXiv folder $folderA not found or not shared", source.lastResult)
    }

    @Test
    fun syncingStateCoversRunAndDuplicateRequestsAreIgnored() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        gate = CompletableDeferred()

        val job = sources.sync(id)!!
        assertEquals(setOf(id), sources.syncing.value)
        assertNull(sources.sync(id))

        gate!!.complete(Unit)
        job.join()

        assertEquals(emptySet<Long>(), sources.syncing.value)
        assertEquals(2, paperDao.papers.value.size)
        assertEquals("2 added", source(folderA).lastResult)
    }

    @Test
    fun syncAllRunsEverySourceOneAtATime() = runBlocking {
        sourceDao.insert(FolderSource(folderId = folderA))
        sourceDao.insert(FolderSource(folderId = folderB))

        sources.syncAll().join()

        assertEquals(1, maxRunning.get())
        assertEquals("Sortes", source(folderA).name)
        assertEquals("Other", source(folderB).name)
        assertEquals(3, paperDao.papers.value.size)
    }

    @Test
    fun syncAllSkipsSourcesAlreadySyncing() = runBlocking {
        val a = sourceDao.insert(FolderSource(folderId = folderA))
        sourceDao.insert(FolderSource(folderId = folderB))
        gate = CompletableDeferred()
        val first = sources.sync(a)!!

        val all = sources.syncAll()
        gate!!.complete(Unit)
        first.join()
        all.join()

        assertEquals(emptySet<Long>(), sources.syncing.value)
        assertTrue(sourceDao.sources.value.all { it.lastSyncedAt == 1_000L })
    }

    @Test
    fun sourceRemovedWhileQueuedIsNotRecreated() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        gate = CompletableDeferred()
        val blocker = sources.sync(sourceDao.insert(FolderSource(folderId = folderB)))!!
        val queued = sources.sync(id)!!

        sources.remove(id).join()
        gate!!.complete(Unit)
        blocker.join()
        queued.join()

        assertEquals(listOf(folderB), sourceDao.sources.value.map { it.folderId })
        assertEquals(emptySet<Long>(), sources.syncing.value)
    }

    @Test
    fun removeKeepsImportedPapers() = runBlocking {
        sources.addAndSync(folderA).join()

        sources.remove(source(folderA).id).join()

        assertTrue(sourceDao.sources.value.isEmpty())
        assertEquals(2, paperDao.papers.value.size)
    }
}
