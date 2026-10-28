package dev.sortes.app.pdf

import dev.sortes.app.data.Paper
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PdfSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var cacheDir: File
    private lateinit var libraryDir: File
    private lateinit var source: PdfSource

    @Before
    fun setUp() {
        server.start()
        cacheDir = File(tmp.root, "pdfs")
        libraryDir = File(tmp.root, "papers")
        source = PdfSource(cacheDir, libraryDir)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun paper(id: Long, url: String? = server.url("/paper.pdf").toString()) =
        Paper(id = id, title = "T", authors = emptyList(), abstract = "", year = null, venue = null, pdfUrl = url)

    private fun pdfResponse(bytes: ByteArray = PDF_BYTES) =
        MockResponse().setBody(Buffer().write(bytes))

    // Regression: File.createTempFile rejected the 2-char prefix "1-" for one-digit ids.
    @Test
    fun downloadsPaperWithSingleDigitId() {
        server.enqueue(pdfResponse())

        val file = runBlocking { source.fetch(paper(id = 1)) }

        assertEquals(File(cacheDir, "1.pdf"), file)
        assertArrayEquals(PDF_BYTES, file.readBytes())
    }

    @Test
    fun secondFetchIsServedFromCache() {
        server.enqueue(pdfResponse())

        val first = runBlocking { source.fetch(paper(id = 42)) }
        val second = runBlocking { source.fetch(paper(id = 42)) }

        assertEquals(first, second)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectsNonPdfResponseAndCachesNothing() {
        server.enqueue(MockResponse().setBody("<html>captive portal</html>"))

        assertThrows(IOException::class.java) { runBlocking { source.fetch(paper(id = 1)) } }
        assertCacheEmpty()
    }

    @Test
    fun httpErrorFailsAndCachesNothing() {
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrows(IOException::class.java) { runBlocking { source.fetch(paper(id = 1)) } }
        assertCacheEmpty()
    }

    @Test
    fun failedDownloadCanBeRetried() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(pdfResponse())

        assertThrows(IOException::class.java) { runBlocking { source.fetch(paper(id = 1)) } }
        val file = runBlocking { source.fetch(paper(id = 1)) }

        assertArrayEquals(PDF_BYTES, file.readBytes())
    }

    @Test
    fun paperWithoutUrlFails() {
        assertThrows(IOException::class.java) { runBlocking { source.fetch(paper(id = 1, url = null)) } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun evictRemovesCachedFile() {
        server.enqueue(pdfResponse())
        server.enqueue(pdfResponse())
        runBlocking { source.fetch(paper(id = 1)) }

        source.evict(paper(id = 1))
        runBlocking { source.fetch(paper(id = 1)) }

        assertEquals(2, server.requestCount)
    }

    private fun localPaper(id: Long, name: String, url: String? = null) =
        paper(id, url).copy(localFile = name)

    private fun importedFile(name: String) =
        File(libraryDir.apply { mkdirs() }, name).apply { writeBytes(PDF_BYTES) }

    @Test
    fun localFileIsServedWithoutNetwork() {
        val imported = importedFile("paper-123.pdf")

        val file = runBlocking { source.fetch(localPaper(id = 7, name = "paper-123.pdf")) }

        assertEquals(imported, file)
        assertEquals(0, server.requestCount)
        assertCacheEmpty()
    }

    @Test
    fun localFileTakesPrecedenceOverUrl() {
        val imported = importedFile("paper-1.pdf")

        val file = runBlocking {
            source.fetch(localPaper(id = 7, name = "paper-1.pdf", url = server.url("/x.pdf").toString()))
        }

        assertEquals(imported, file)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingLocalFileFailsWithoutFallingBackToNetwork() {
        val e = assertThrows(IOException::class.java) {
            runBlocking {
                source.fetch(localPaper(id = 7, name = "gone.pdf", url = server.url("/x.pdf").toString()))
            }
        }
        assertEquals("Imported PDF is missing", e.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun localFileNameCannotEscapeLibrary() {
        File(tmp.root, "outside.pdf").writeBytes(PDF_BYTES)
        libraryDir.mkdirs()

        for (name in listOf("../outside.pdf", "/etc/passwd", "..", ".", "")) {
            assertThrows(name, IOException::class.java) {
                runBlocking { source.fetch(localPaper(id = 7, name = name)) }
            }
        }
    }

    @Test
    fun evictNeverDeletesImportedFile() {
        val imported = importedFile("paper-9.pdf")
        // A cached download for the same id must not be confused with the import either.
        cacheDir.mkdirs()
        File(cacheDir, "7.pdf").writeBytes(PDF_BYTES)

        source.evict(localPaper(id = 7, name = "paper-9.pdf"))

        assertTrue(imported.exists())
        assertArrayEquals(PDF_BYTES, imported.readBytes())
    }

    private fun assertCacheEmpty() {
        assertEquals(emptyList<String>(), cacheDir.list()?.toList().orEmpty())
    }

    private companion object {
        val PDF_BYTES = "%PDF-1.4\nnot a real pdf body\n%%EOF\n".toByteArray()
    }
}
