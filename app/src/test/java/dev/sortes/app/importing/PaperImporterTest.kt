package dev.sortes.app.importing

import dev.sortes.app.arxiv.ArxivClient
import dev.sortes.app.arxiv.ArxivEntry
import dev.sortes.app.data.Paper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class PaperImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakePaperDao()
    private val fetched = mutableListOf<String>()
    private var arxiv = ArxivClient { id ->
        fetched += id
        ENTRY
    }
    private lateinit var libraryDir: File

    private fun importer(maxPdfBytes: Long = PaperImporter.MAX_PDF_BYTES) =
        PaperImporter(dao, { arxiv.fetch(it) }, libraryDir, maxPdfBytes)

    @Before
    fun setUp() {
        libraryDir = File(tmp.root, "papers")
    }

    // --- arXiv ---

    @Test
    fun importsArxivPaper() {
        val paper = runBlocking { importer().importArxiv("https://arxiv.org/abs/1706.03762v7") }

        assertEquals(listOf("1706.03762"), fetched)
        assertEquals(
            Paper(
                id = 1,
                title = "Attention Is All You Need",
                authors = listOf("Ashish Vaswani", "Noam Shazeer"),
                abstract = "The dominant sequence transduction models.",
                year = 2017,
                venue = "arXiv",
                pdfUrl = "https://arxiv.org/pdf/1706.03762",
            ),
            paper,
        )
        assertEquals(listOf(paper), dao.papers.value)
    }

    @Test
    fun reimportingSameIdReturnsExistingPaper() {
        val first = runBlocking { importer().importArxiv("1706.03762") }
        val second = runBlocking { importer().importArxiv("arxiv.org/pdf/1706.03762") }

        assertEquals(first, second)
        assertEquals(1, dao.papers.value.size)
        assertEquals(1, fetched.size)
    }

    @Test
    fun dedupesAcrossVersionsInBothDirections() {
        runBlocking { importer().importArxiv("1706.03762") }
        val versioned = runBlocking { importer().importArxiv("1706.03762v7") }
        runBlocking { importer().importArxiv("1512.03385v2") }
        val unversioned = runBlocking { importer().importArxiv("https://arxiv.org/abs/1512.03385") }

        assertEquals(1L, versioned.id)
        assertEquals(2L, unversioned.id)
        assertEquals(2, dao.papers.value.size)
        assertEquals(listOf("1706.03762", "1512.03385"), fetched)
    }

    @Test
    fun dedupesAgainstSeededPapers() {
        // The seed data uses the same versionless URL form.
        runBlocking {
            dao.insert(
                Paper(
                    title = "Seed", authors = emptyList(), abstract = "", year = 2017, venue = "NeurIPS",
                    pdfUrl = "https://arxiv.org/pdf/1706.03762",
                ),
            )
        }

        val paper = runBlocking { importer().importArxiv("1706.03762v5") }

        assertEquals("Seed", paper.title)
        assertEquals(emptyList<String>(), fetched)
    }

    @Test
    fun concurrentImportsOfSamePaperInsertOnce() {
        val gate = CompletableDeferred<Unit>()
        arxiv = ArxivClient { id ->
            fetched += id
            gate.await()
            ENTRY
        }
        val importer = importer()

        val papers = runBlocking {
            val a = async { importer.importArxiv("1706.03762") }
            val b = async { importer.importArxiv("1706.03762v2") }
            // Let both imports run up to the (blocked) network call before it returns.
            repeat(3) { yield() }
            gate.complete(Unit)
            listOf(a.await(), b.await())
        }

        assertEquals(papers[0], papers[1])
        assertEquals(1, dao.papers.value.size)
    }

    @Test
    fun separateImportersSharingTheDatabaseInsertOnce() {
        // E.g. two MainActivity instances: their mutexes don't see each other, the unique index does.
        val gate = CompletableDeferred<Unit>()
        arxiv = ArxivClient { id ->
            fetched += id
            gate.await()
            ENTRY
        }

        val papers = runBlocking {
            val a = async { importer().importArxiv("1706.03762") }
            val b = async { importer().importArxiv("1706.03762v7") }
            repeat(3) { yield() }
            gate.complete(Unit)
            listOf(a.await(), b.await())
        }

        assertEquals(2, fetched.size) // both got past the duplicate check
        assertEquals(papers[0], papers[1])
        assertEquals(listOf(papers[0]), dao.papers.value)
    }

    @Test
    fun invalidInputFailsWithoutNetwork() {
        val e = assertThrows(IOException::class.java) { runBlocking { importer().importArxiv("hello") } }
        assertEquals("Not an arXiv ID or URL", e.message)
        assertEquals(emptyList<String>(), fetched)
        assertEquals(emptyList<Paper>(), dao.papers.value)
    }

    @Test
    fun lookupFailurePropagatesAndSavesNothing() {
        arxiv = ArxivClient { throw IOException("arXiv lookup failed: HTTP 503") }

        val e = assertThrows(IOException::class.java) {
            runBlocking { importer().importArxiv("1706.03762") }
        }
        assertEquals("arXiv lookup failed: HTTP 503", e.message)
        assertEquals(emptyList<Paper>(), dao.papers.value)
    }

    // --- local PDFs ---

    @Test
    fun importsPdfIntoLibrary() {
        val paper = runBlocking { importer().importPdf("My Paper.pdf") { PDF_BYTES.inputStream() } }

        assertEquals("My Paper", paper.title)
        assertNull(paper.pdfUrl)
        val file = File(libraryDir, paper.localFile!!)
        assertArrayEquals(PDF_BYTES, file.readBytes())
        assertEquals(listOf(paper), dao.papers.value)
    }

    @Test
    fun eachPdfImportGetsItsOwnFile() {
        val a = runBlocking { importer().importPdf("a.pdf") { PDF_BYTES.inputStream() } }
        val b = runBlocking { importer().importPdf("a.pdf") { PDF_BYTES.inputStream() } }

        assertTrue(a.localFile != b.localFile)
        assertEquals(2, libraryDir.list()!!.size)
    }

    @Test
    fun streamIsOpenedOnceAndClosed() {
        var opens = 0
        var closed = false
        val stream = object : ByteArrayInputStream(PDF_BYTES) {
            override fun close() {
                closed = true
                super.close()
            }
        }

        runBlocking {
            importer().importPdf("x.pdf") {
                opens++
                stream
            }
        }

        assertEquals(1, opens)
        assertTrue(closed)
    }

    @Test
    fun rejectsNonPdfBeforeWritingAnything() {
        val e = assertThrows(IOException::class.java) {
            runBlocking { importer().importPdf("evil.pdf") { "<html></html>".byteInputStream() } }
        }
        assertEquals("Not a PDF file", e.message)
        assertFalse(libraryDir.exists())
        assertLibraryEmpty()
    }

    @Test
    fun headerSplitAcrossReadsIsAccepted() {
        val trickle = object : InputStream() {
            val bytes = PDF_BYTES.inputStream()
            override fun read(): Int = bytes.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = bytes.read(b, off, minOf(len, 1))
        }

        val paper = runBlocking { importer().importPdf("x.pdf") { trickle } }

        assertArrayEquals(PDF_BYTES, File(libraryDir, paper.localFile!!).readBytes())
    }

    @Test
    fun pdfAtSizeLimitIsAccepted() {
        val paper = runBlocking {
            importer(maxPdfBytes = PDF_BYTES.size.toLong()).importPdf("x.pdf") { PDF_BYTES.inputStream() }
        }
        assertArrayEquals(PDF_BYTES, File(libraryDir, paper.localFile!!).readBytes())
    }

    @Test
    fun oversizedPdfIsRejectedAndRemoved() {
        val e = assertThrows(IOException::class.java) {
            runBlocking {
                importer(maxPdfBytes = 1024).importPdf("x.pdf") { EndlessPdf() }
            }
        }
        assertEquals("PDF is too large", e.message)
        assertLibraryEmpty()
    }

    @Test
    fun cancellingDuringCopyStopsAndRemovesFile() {
        lateinit var job: Job
        val stream = EndlessPdf(onRead = { reads -> if (reads == 100) job.cancel() })

        runBlocking {
            job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { importer().importPdf("x.pdf") { stream } }
            job.join()
        }

        assertTrue(job.isCancelled)
        assertTrue("kept reading after cancel: ${stream.reads}", stream.reads < 200)
        assertLibraryEmpty()
    }

    @Test
    fun cancellationRightAfterInsertKeepsFileForTheRow() {
        lateinit var job: Job
        dao.afterInsert = {
            job.cancel()
            yield() // a cancellable suspension point, as when Room resumes the caller
        }

        runBlocking {
            job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                importer().importPdf("x.pdf") { PDF_BYTES.inputStream() }
            }
            job.join()
        }

        val row = dao.papers.value.single()
        assertArrayEquals(PDF_BYTES, File(libraryDir, row.localFile!!).readBytes())
    }

    @Test
    fun rejectsEmptyFile() {
        assertThrows(IOException::class.java) {
            runBlocking { importer().importPdf("empty.pdf") { ByteArray(0).inputStream() } }
        }
        assertLibraryEmpty()
    }

    @Test
    fun unopenableStreamFails() {
        assertThrows(IOException::class.java) { runBlocking { importer().importPdf("x.pdf") { null } } }
        assertLibraryEmpty()
    }

    @Test
    fun readErrorMidCopyLeavesNoFile() {
        val failing = object : InputStream() {
            var n = 0
            override fun read(): Int = if (n < PDF_BYTES.size) PDF_BYTES[n++].toInt() else throw IOException("read failed")
        }

        assertThrows(IOException::class.java) { runBlocking { importer().importPdf("x.pdf") { failing } } }
        assertLibraryEmpty()
    }

    @Test
    fun databaseFailureRemovesCopiedFile() {
        dao.failInserts = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking { importer().importPdf("x.pdf") { PDF_BYTES.inputStream() } }
        }
        assertLibraryEmpty()
    }

    @Test
    fun titleFromDisplayName() {
        assertEquals("Paper", PaperImporter.titleFrom("Paper.pdf"))
        assertEquals("Paper", PaperImporter.titleFrom("Paper.PDF"))
        assertEquals("a.pdf notes", PaperImporter.titleFrom("a.pdf notes"))
        assertEquals("Lecture 3", PaperImporter.titleFrom("  Lecture 3 .pdf "))
        assertEquals("Untitled PDF", PaperImporter.titleFrom(".pdf"))
        assertEquals("Untitled PDF", PaperImporter.titleFrom(""))
        assertEquals("Untitled PDF", PaperImporter.titleFrom(null))
    }

    /** A valid PDF header followed by bytes forever. */
    private class EndlessPdf(private val onRead: (Int) -> Unit = {}) : InputStream() {
        var reads = 0
        private var pos = 0
        override fun read(): Int = if (pos < 4) "%PDF"[pos++].code else 0
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < 4) return super.read(b, off, minOf(len, 4 - pos))
            onRead(++reads)
            b.fill(0, off, off + len)
            return len
        }
    }

    private fun assertLibraryEmpty() {
        assertEquals(emptyList<String>(), libraryDir.list()?.toList().orEmpty())
        assertEquals(emptyList<Paper>(), dao.papers.value)
    }

    private companion object {
        val ENTRY = ArxivEntry(
            title = "Attention Is All You Need",
            authors = listOf("Ashish Vaswani", "Noam Shazeer"),
            abstract = "The dominant sequence transduction models.",
            year = 2017,
        )
        val PDF_BYTES = "%PDF-1.4\nnot a real pdf body\n%%EOF\n".toByteArray()
    }
}
