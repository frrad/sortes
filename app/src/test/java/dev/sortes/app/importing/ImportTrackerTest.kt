package dev.sortes.app.importing

import dev.sortes.app.data.Paper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ImportTrackerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tracker = ImportTracker(scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun paper(title: String) =
        Paper(title = title, authors = emptyList(), abstract = "", year = null, venue = null)

    @Test
    fun reportsRunningThenResult() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val job = tracker.launch {
            gate.await()
            paper("A")
        }
        assertEquals(ImportStatus.Running(1), tracker.status.value)

        gate.complete(Unit)
        job.join()

        assertEquals(ImportStatus.Done("A"), tracker.status.value)
    }

    @Test
    fun reportsFailureMessage() = runBlocking {
        tracker.launch { throw IOException("Not a PDF file") }.join()

        assertEquals(ImportStatus.Failed("Not a PDF file"), tracker.status.value)
    }

    @Test
    fun finishedImportDoesNotHideOneStillRunning() = runBlocking {
        val slowGate = CompletableDeferred<Unit>()
        val slow = tracker.launch {
            slowGate.await()
            paper("Slow")
        }
        val fast = tracker.launch { paper("Fast") }
        fast.join()

        assertEquals(ImportStatus.Running(1), tracker.status.value)

        slowGate.complete(Unit)
        slow.join()
        assertEquals(ImportStatus.Done("Slow"), tracker.status.value)
    }

    @Test
    fun countsOverlappingImports() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val jobs = List(3) {
            tracker.launch {
                gate.await()
                paper("P$it")
            }
        }
        assertEquals(ImportStatus.Running(3), tracker.status.value)

        gate.complete(Unit)
        jobs.forEach { it.join() }
        assertTrue(tracker.status.value is ImportStatus.Done)
    }

    @Test
    fun dismissIsIgnoredWhileRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val job = tracker.launch {
            gate.await()
            paper("A")
        }

        tracker.dismiss()
        assertEquals(ImportStatus.Running(1), tracker.status.value)

        gate.complete(Unit)
        job.join()
        tracker.dismiss()
        assertNull(tracker.status.value)
    }

    @Test
    fun cancelledImportIsUncountedAndShowsPreviousResult() = runBlocking {
        tracker.launch { paper("Earlier") }.join()
        val job = tracker.launch { awaitCancellation() }
        assertEquals(ImportStatus.Running(1), tracker.status.value)

        job.cancel()
        job.join()

        assertEquals(ImportStatus.Done("Earlier"), tracker.status.value)
    }

    @Test
    fun outlivesTheCallersScope() = runBlocking {
        // The caller (e.g. a ViewModel) going away must not cancel the import.
        val caller = CoroutineScope(SupervisorJob())
        val gate = CompletableDeferred<Unit>()
        lateinit var job: Job
        caller.launch {
            job = tracker.launch {
                gate.await()
                paper("Shared")
            }
        }.join()
        caller.cancel()

        gate.complete(Unit)
        job.join()

        assertEquals(ImportStatus.Done("Shared"), tracker.status.value)
    }
}
