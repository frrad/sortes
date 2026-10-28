package dev.sortes.app.arxiv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HttpArxivClientTest {

    private val server = MockWebServer()
    private lateinit var client: HttpArxivClient

    @Before
    fun setUp() {
        server.start()
        client = HttpArxivClient(server.url("/api/query").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fetchesAndParsesEntry() {
        server.enqueue(MockResponse().setBody(ArxivAtomTest.ATTENTION_FEED))

        val entry = runBlocking { client.fetch("1706.03762") }

        assertEquals("Attention Is All You Need", entry.title)
        assertEquals(2017, entry.year)
        val request = server.takeRequest()
        assertEquals("/api/query?id_list=1706.03762&max_results=1", request.path)
    }

    @Test
    fun oldStyleIdIsSentAsIs() {
        server.enqueue(MockResponse().setBody(ArxivAtomTest.ATTENTION_FEED))

        runBlocking { client.fetch("hep-th/9901001") }

        assertEquals("/api/query?id_list=hep-th/9901001&max_results=1", server.takeRequest().path)
    }

    @Test
    fun httpErrorThrows() {
        server.enqueue(MockResponse().setResponseCode(503))

        val e = assertThrows(IOException::class.java) { runBlocking { client.fetch("1706.03762") } }
        assertEquals("arXiv lookup failed: HTTP 503", e.message)
    }

    @Test
    fun emptyResultThrows() {
        server.enqueue(MockResponse().setBody(ArxivAtomTest.feed("")))

        assertThrows(IOException::class.java) { runBlocking { client.fetch("1706.03762") } }
    }

    @Test
    fun droppedConnectionThrowsIOException() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(IOException::class.java) { runBlocking { client.fetch("1706.03762") } }
    }

    @Test
    fun defaultEndpointIsHttpsExportApi() {
        assertEquals("https://export.arxiv.org/api/query", HttpArxivClient.DEFAULT_BASE_URL)
    }
}
