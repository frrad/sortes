package dev.sortes.app.importing

import dev.sortes.app.data.Paper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.CoroutineContext

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

    // Regression: the status was settled in a completion handler, but join() on a job that has
    // already completed returns without waiting for its handlers, so a test (or caller) that
    // joined an import could still see it running. Unconfined runs the body inside launch(), so
    // the body's handler is registered first and sees the status as soon as the job completes.
    @Test
    fun statusIsSettledByTheTimeTheImportCompletes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tracker = ImportTracker(scope)
        var seen: ImportStatus? = null
        val job = tracker.launch {
            currentCoroutineContext().job.invokeOnCompletion { seen = tracker.status.value }
            paper("A")
        }

        assertTrue(job.isCompleted)
        assertEquals(ImportStatus.Done("A"), seen)
        scope.cancel()
    }

    @Test
    fun statusIsSettledByTheTimeACancelledImportCompletes() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tracker = ImportTracker(scope)
        tracker.launch { paper("Earlier") }
        var seen: ImportStatus? = null
        val job = tracker.launch {
            currentCoroutineContext().job.invokeOnCompletion { seen = tracker.status.value }
            awaitCancellation()
        }
        assertEquals(ImportStatus.Running(1), tracker.status.value)

        job.cancel()

        assertTrue(job.isCompleted)
        assertEquals(ImportStatus.Done("Earlier"), seen)
        scope.cancel()
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
    fun importCancelledWhileRunningIsUncountedAndShowsPreviousResult() = runBlocking {
        tracker.launch { paper("Earlier") }.join()
        val started = CompletableDeferred<Unit>()
        val job = tracker.launch {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()
        assertEquals(ImportStatus.Running(1), tracker.status.value)

        job.cancel()
        job.join()

        assertEquals(ImportStatus.Done("Earlier"), tracker.status.value)
    }

    // Regression: the counter was decremented in the body's finally, which never runs for a job
    // cancelled before it is dispatched, so the status stayed at "Importing…" forever.
    @Test
    fun importCancelledBeforeItStartsIsUncounted() {
        val dispatcher = QueueDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tracker = ImportTracker(scope)
        tracker.launch { paper("Earlier") }
        dispatcher.runAll()
        assertEquals(ImportStatus.Done("Earlier"), tracker.status.value)

        var ran = false
        val job = tracker.launch {
            ran = true
            paper("Never")
        }
        assertEquals(ImportStatus.Running(1), tracker.status.value)
        job.cancel() // still queued: its body hasn't started
        dispatcher.runAll()

        assertFalse(ran)
        assertTrue(job.isCancelled && job.isCompleted)
        assertEquals(ImportStatus.Done("Earlier"), tracker.status.value)
        scope.cancel()
    }

    @Test
    fun importLaunchedIntoCancelledScopeIsUncounted() {
        val dispatcher = QueueDispatcher()
        val tracker = ImportTracker(CoroutineScope(Job().apply { cancel() } + dispatcher))

        val job = tracker.launch { paper("Never") }
        dispatcher.runAll()

        assertTrue(job.isCompleted)
        assertNull(tracker.status.value)
    }

    /** Runs dispatched coroutines only when told to, so tests control exactly when bodies start. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
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
