package dev.sortes.app.alphaxiv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HttpAlphaxivClientTest {

    private val folderId = "01a1838a-db89-71d0-956c-e8067fdfee2a"
    private val server = MockWebServer()
    private lateinit var client: HttpAlphaxivClient

    @Before
    fun setUp() {
        server.start()
        client = HttpAlphaxivClient(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/alphaxiv/$name")) { "missing fixture $name" }.readText()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun page(vararg ids: String, next: String? = null) =
        json("""{"papers":[${ids.joinToString(",") { """{"universalId":"$it"}""" }}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")

    private fun fetch() = runBlocking { client.fetchFolder(folderId) }

    @Test
    fun fetchesSinglePageFolder() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(json(fixture("shared_folder_papers.json")))

        val folder = fetch()

        assertEquals("Sortes", folder.name)
        assertEquals(listOf("2311.08105", "2609.20807", "2602.03839", "2512.03442"), folder.paperIds)
        assertEquals("/folders/v4/shared/$folderId", server.takeRequest().path)
        assertEquals("/folders/v4/shared/$folderId/papers", server.takeRequest().path)
    }

    @Test
    fun followsCursorsAcrossPages() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(page("2401.00001", "2401.00002", next = "c1"))
        server.enqueue(page("2401.00003", next = "c/2+x"))
        server.enqueue(page("2401.00004"))

        val folder = fetch()

        assertEquals(listOf("2401.00001", "2401.00002", "2401.00003", "2401.00004"), folder.paperIds)
        server.takeRequest()
        assertEquals("/folders/v4/shared/$folderId/papers", server.takeRequest().path)
        assertEquals("/folders/v4/shared/$folderId/papers?cursor=c1", server.takeRequest().path)
        // Cursors are opaque; they must be URL-encoded.
        assertEquals("/folders/v4/shared/$folderId/papers?cursor=c%2F2%2Bx", server.takeRequest().path)
    }

    @Test
    fun dropsDuplicateIdsAcrossPages() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(page("2401.00001", "2401.00002", next = "c1"))
        server.enqueue(page("2401.00002", "2401.00003"))

        assertEquals(listOf("2401.00001", "2401.00002", "2401.00003"), fetch().paperIds)
    }

    @Test
    fun emptyFolder() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(page())

        assertEquals(emptyList<String>(), fetch().paperIds)
    }

    @Test
    fun missingFolderNameIsNotFatal() {
        server.enqueue(json("""{"folders":[]}"""))
        server.enqueue(page("2401.00001"))

        val folder = fetch()

        assertNull(folder.name)
        assertEquals(listOf("2401.00001"), folder.paperIds)
    }

    @Test
    fun notFoundFolder() {
        server.enqueue(json("""{"error":{"message":"Folder not found","collection":"folder"}}""", code = 404))

        assertThrows(AlphaxivFolderNotFoundException::class.java) { fetch() }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun malformedFolderIdIsReportedAsNotFound() {
        server.enqueue(json("""{"error":{"message":"Request does not match schema."}}""", code = 400))

        assertThrows(AlphaxivFolderNotFoundException::class.java) { fetch() }
    }

    @Test
    fun badCursorOnLaterPageIsAPlainFailureNotNotFound() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(page("2401.00001", next = "c1"))
        server.enqueue(json("""{"error":{"message":"Invalid cursor"}}""", code = 400))

        val e = assertThrows(IOException::class.java) { fetch() }
        assertFalse(e is AlphaxivFolderNotFoundException)
    }

    @Test
    fun serverError() {
        server.enqueue(json("{}", code = 500))

        val e = assertThrows(IOException::class.java) { fetch() }
        assertFalse(e is AlphaxivFolderNotFoundException)
        assertTrue(e.message!!.contains("500"))
    }

    @Test
    fun serverErrorOnPapersPage() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(json("{}", code = 503))

        assertThrows(IOException::class.java) { fetch() }
    }

    @Test
    fun htmlInsteadOfJson() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))

        assertThrows(IOException::class.java) { fetch() }
    }

    @Test
    fun connectionDropped() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertThrows(IOException::class.java) { fetch() }
    }

    @Test
    fun repeatedCursorStopsInsteadOfLooping() {
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(page("2401.00001", next = "same"))
        server.enqueue(page("2401.00002", next = "same"))

        assertThrows(IOException::class.java) { fetch() }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun pageCapStopsEndlessPagination() {
        server.enqueue(json(fixture("shared_folder.json")))
        repeat(HttpAlphaxivClient.MAX_PAGES) { server.enqueue(page("2401.%05d".format(it), next = "c$it")) }

        assertThrows(IOException::class.java) { fetch() }
        assertEquals(1 + HttpAlphaxivClient.MAX_PAGES, server.requestCount)
    }

    @Test
    fun toleratesTrailingSlashInBaseUrl() {
        client = HttpAlphaxivClient(server.url("/").toString().trimEnd('/') + "/")
        server.enqueue(json(fixture("shared_folder.json")))
        server.enqueue(page())

        fetch()

        assertEquals("/folders/v4/shared/$folderId", server.takeRequest().path)
    }
}
