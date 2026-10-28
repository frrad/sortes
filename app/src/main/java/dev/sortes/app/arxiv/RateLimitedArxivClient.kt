package dev.sortes.app.arxiv

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends requests to [delegate] one at a time, starting each at least [intervalMillis] after the
 * previous one finished, as the arXiv API terms ask (no more than one request every 3 s, on a
 * single connection). Use one instance for the whole process.
 */
class RateLimitedArxivClient(
    private val delegate: ArxivClient,
    private val intervalMillis: Long = 3_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : ArxivClient {

    private val mutex = Mutex()
    private var lastFinished: Long? = null

    override suspend fun fetch(id: String): ArxivEntry = mutex.withLock {
        lastFinished?.let { last ->
            val wait = last + intervalMillis - clock()
            if (wait > 0) sleep(wait)
        }
        try {
            delegate.fetch(id)
        } finally {
            // Failed requests count too: arXiv saw them.
            lastFinished = clock()
        }
    }
}
