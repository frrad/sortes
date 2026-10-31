package dev.sortes.app.arxiv

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RateLimitedArxivClientTest {

    private var now = 0L
    private val sleeps = mutableListOf<Long>()
    private val fetched = mutableListOf<String>()
    private var delegate = ArxivClient { id ->
        fetched += id
        ENTRY
    }

    private val client =
        RateLimitedArxivClient(
            delegate = { delegate.fetch(it) },
            intervalMillis = 3_000,
            clock = { now },
            sleep = {
                sleeps += it
                now += it
            },
        )

    private fun fetch(id: String) = runBlocking { client.fetch(id) }

    @Test
    fun firstRequestIsNotDelayed() {
        fetch("a")

        assertEquals(emptyList<Long>(), sleeps)
        assertEquals(listOf("a"), fetched)
    }

    @Test
    fun backToBackRequestsAreSpaced() {
        fetch("a")
        fetch("b")
        fetch("c")

        assertEquals(listOf(3_000L, 3_000L), sleeps)
        assertEquals(listOf("a", "b", "c"), fetched)
    }

    @Test
    fun waitsOnlyForTheRemainder() {
        fetch("a")
        now += 2_000
        fetch("b")

        assertEquals(listOf(1_000L), sleeps)
    }

    @Test
    fun noWaitAfterLongGap() {
        fetch("a")
        now += 10_000
        fetch("b")

        assertEquals(emptyList<Long>(), sleeps)
    }

    @Test
    fun intervalCountsFromWhenTheRequestFinished() {
        delegate = ArxivClient {
            now += 5_000
            ENTRY
        } // a slow request
        fetch("a")
        fetch("b")

        assertEquals(listOf(3_000L), sleeps)
    }

    @Test
    fun failedRequestsStillCount() {
        delegate = ArxivClient { throw IOException("HTTP 503") }
        assertThrows(IOException::class.java) { fetch("a") }
        delegate = ArxivClient { ENTRY }

        fetch("b")

        assertEquals(listOf(3_000L), sleeps)
    }

    @Test
    fun concurrentCallersNeverOverlap() = runBlocking {
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val limited =
            RateLimitedArxivClient(
                {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    try {
                        gate.await()
                        ENTRY
                    } finally {
                        running.decrementAndGet()
                    }
                },
                intervalMillis = 0,
            )

        val calls = (1..3).map { async(Dispatchers.Default) { limited.fetch("$it") } }
        Thread.sleep(100) // give the others a chance to overlap if they could
        gate.complete(Unit)
        calls.forEach { it.await() }

        assertEquals(1, maxRunning.get())
    }

    private companion object {
        val ENTRY = ArxivEntry(title = "T", authors = emptyList(), abstract = "", year = null)
    }
}
