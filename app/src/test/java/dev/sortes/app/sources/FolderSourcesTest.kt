package dev.sortes.app.sources

import dev.sortes.app.alphaxiv.AlphaxivClient
import dev.sortes.app.alphaxiv.AlphaxivFolder
import dev.sortes.app.alphaxiv.AlphaxivFolderNotFoundException
import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.ArxivImport
import dev.sortes.app.importing.FakePaperDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class FolderSourcesTest {

    private val folderA = "01a1838a-db89-71d0-956c-e8067fdfee2a"
    private val folderB = "01a1838a-db89-71d0-956c-e8067fdfee2b"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sourceDao = FakeFolderSourceDao()
    private val paperDao = FakePaperDao()

    private val folders = ConcurrentHashMap(
        mapOf(
            folderA to AlphaxivFolder("Sortes", listOf("2311.08105", "2609.20807")),
            folderB to AlphaxivFolder("Other", listOf("2311.08105", "2512.03442")),
        ),
    )

    /** When set, folder fetches wait on it, so a sync can be observed in flight. */
    @Volatile private var folderGate: CompletableDeferred<Unit>? = null

    /** Folder IDs as their fetch starts. */
    private val folderFetches = Channel<String>(Channel.UNLIMITED)
    private val fetchCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val running = AtomicInteger()
    private val maxRunning = AtomicInteger()
    private val client = AlphaxivClient { id ->
        fetchCounts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            folderFetches.trySend(id)
            folderGate?.await()
            folders[id] ?: throw AlphaxivFolderNotFoundException(id)
        } finally {
            running.decrementAndGet()
        }
    }

    /** When set, paper imports wait on it. */
    @Volatile private var importGate: CompletableDeferred<Unit>? = null
    private val importsStarted = Channel<String>(Channel.UNLIMITED)
    private val imported = CopyOnWriteArrayList<String>()

    private val syncer = FolderSyncer(client) { arxivId ->
        importsStarted.trySend(arxivId.id)
        importGate?.await()
        val paper = Paper(title = arxivId.id, authors = emptyList(), abstract = "", year = null, venue = null,
            pdfUrl = arxivId.pdfUrl)
        val id = paperDao.insert(paper)
        if (id != -1L) imported += arxivId.id
        ArxivImport(paper.copy(id = id), inserted = id != -1L)
    }

    private fun sources(scope: CoroutineScope = this.scope) = FolderSources(scope, sourceDao, syncer, clock = { 1_000L })

    private val sources = sources()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun source(folderId: String) = sourceDao.sources.value.single { it.folderId == folderId }

    private fun fetches(folderId: String) = fetchCounts[folderId]?.get() ?: 0

    private suspend fun <T> Channel<T>.next(): T = withTimeout(5_000) { receive() }

    // --- adding and syncing ---

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

    // --- concurrency ---

    @Test
    fun syncingStateCoversRunAndDuplicateRequestsAreIgnored() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        folderGate = CompletableDeferred()

        val job = sources.sync(id)!!
        assertEquals(setOf(id), sources.syncing.value)
        assertNull(sources.sync(id))

        folderGate!!.complete(Unit)
        job.join()

        assertEquals(emptySet<Long>(), sources.syncing.value)
        assertEquals(1, fetches(folderA))
        assertEquals("2 added", source(folderA).lastResult)
    }

    @Test
    fun syncAllRunsEverySourceOneAtATime() = runBlocking {
        sourceDao.insert(FolderSource(folderId = folderA))
        sourceDao.insert(FolderSource(folderId = folderB))
        folderGate = CompletableDeferred()

        val all = sources.syncAll()
        folderFetches.next()
        // Without the sync lock the second folder's fetch would start during this window.
        Thread.sleep(200)
        assertEquals(1, fetches(folderA) + fetches(folderB))
        folderGate!!.complete(Unit)
        all.join()

        assertEquals(1, maxRunning.get())
        assertEquals("Sortes", source(folderA).name)
        assertEquals("Other", source(folderB).name)
        assertEquals(3, paperDao.papers.value.size)
    }

    @Test
    fun syncAllSkipsSourcesAlreadySyncing() = runBlocking {
        val a = sourceDao.insert(FolderSource(folderId = folderA))
        val b = sourceDao.insert(FolderSource(folderId = folderB))
        folderGate = CompletableDeferred()
        val first = sources.sync(a)!!
        assertEquals(folderA, folderFetches.next())

        val all = sources.syncAll()
        // syncAll runs asynchronously; once B is queued it has already tried (and skipped) A.
        withTimeout(5_000) { sources.syncing.first { b in it } }
        folderGate!!.complete(Unit)
        first.join()
        all.join()

        assertEquals(1, fetches(folderA))
        assertEquals(1, fetches(folderB))
        assertEquals(emptySet<Long>(), sources.syncing.value)
        assertTrue(sourceDao.sources.value.all { it.lastSyncedAt == 1_000L })
    }

    // --- removing ---

    @Test
    fun sourceRemovedWhileQueuedIsNeverFetched() = runBlocking {
        val a = sourceDao.insert(FolderSource(folderId = folderA))
        val b = sourceDao.insert(FolderSource(folderId = folderB))
        folderGate = CompletableDeferred()
        val blocker = sources.sync(b)!!
        assertEquals(folderB, folderFetches.next()) // B holds the sync lock
        val queued = sources.sync(a)!!

        sources.remove(a).join()
        folderGate!!.complete(Unit)
        blocker.join()
        queued.join()

        assertEquals(0, fetches(folderA))
        assertEquals(listOf("2311.08105", "2512.03442"), imported) // B's papers only
        assertEquals(listOf(folderB), sourceDao.sources.value.map { it.folderId })
        assertEquals(emptySet<Long>(), sources.syncing.value)
    }

    @Test
    fun removingASourceStopsItsRunningSync() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        importGate = CompletableDeferred()
        val job = sources.sync(id)!!
        assertEquals("2311.08105", importsStarted.next())

        sources.remove(id).join()

        assertTrue(job.isCancelled)
        importGate!!.complete(Unit)
        assertEquals(emptyList<String>(), imported) // neither paper was added
        assertTrue(sourceDao.sources.value.isEmpty())
        assertEquals(emptySet<Long>(), sources.syncing.value)
    }

    @Test
    fun syncCancelledBeforeItRunsIsNotLeftSyncing() = runBlocking {
        // A dispatcher that runs tasks only when asked, so the job is cancelled before its body
        // starts; then the coroutine completes without ever entering it.
        val queue = ArrayDeque<Runnable>()
        val manual = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queue.addLast(block)
            }
        }
        val manualScope = CoroutineScope(SupervisorJob() + manual)
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        val sources = sources(manualScope)

        val job = sources.sync(id)!!
        job.cancel()
        while (queue.isNotEmpty()) queue.removeFirst().run()

        assertTrue(job.isCompleted)
        assertEquals(emptySet<Long>(), sources.syncing.value)
        assertEquals(0, fetches(folderA))
        manualScope.cancel()
    }

    @Test
    fun removeKeepsImportedPapers() = runBlocking {
        sources.addAndSync(folderA).join()

        sources.remove(source(folderA).id).join()

        assertTrue(sourceDao.sources.value.isEmpty())
        assertEquals(2, paperDao.papers.value.size)
    }

    // --- database errors are reported, not thrown into the scope ---

    @Test
    fun saveFailureIsReported() = runBlocking {
        sourceDao.failing += "insert"

        sources.addAndSync(folderA).join()

        assertEquals("Couldn't save source: disk I/O error", sources.error.value)
        assertEquals(0, fetches(folderA))
        assertTrue(scope.isActiveScope())
    }

    @Test
    fun recordFailureIsReportedAndSyncStateCleared() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        sourceDao.failing += "recordSync"

        sources.sync(id)!!.join()

        assertEquals("Couldn't save sync result: disk I/O error", sources.error.value)
        assertEquals(emptySet<Long>(), sources.syncing.value)
        // The scope survives, so later syncs still work.
        sourceDao.failing.clear()
        sources.sync(id)!!.join()
        assertEquals("2 already in library", source(folderA).lastResult)
    }

    @Test
    fun loadFailuresAreReported() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        sourceDao.failing += "findById"
        sources.sync(id)!!.join()
        assertEquals("Couldn't load source: disk I/O error", sources.error.value)

        sourceDao.failing += "getAll"
        sources.syncAll().join()
        assertEquals("Couldn't load sources: disk I/O error", sources.error.value)
        assertEquals(0, fetches(folderA))
    }

    @Test
    fun removeFailureIsReportedAndErrorDismissable() = runBlocking {
        val id = sourceDao.insert(FolderSource(folderId = folderA))
        sourceDao.failing += "delete"

        sources.remove(id).join()

        assertEquals("Couldn't remove source: disk I/O error", sources.error.value)
        sources.dismissError()
        assertNull(sources.error.value)
        assertFalse(sourceDao.sources.value.isEmpty())
    }

    private fun CoroutineScope.isActiveScope() = coroutineContext[kotlinx.coroutines.Job]!!.isActive
}
