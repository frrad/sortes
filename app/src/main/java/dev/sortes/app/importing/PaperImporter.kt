package dev.sortes.app.importing

import android.content.Context
import dev.sortes.app.arxiv.ArxivClient
import dev.sortes.app.arxiv.ArxivId
import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Result of an arXiv import: the paper, and whether this call added it to the library. */
data class ArxivImport(val paper: Paper, val inserted: Boolean)

/** Adds papers to the library from arXiv or from PDF files. Failures throw [IOException]. */
class PaperImporter(
    private val dao: PaperDao,
    private val arxiv: ArxivClient,
    private val libraryDir: File,
    private val maxPdfBytes: Long = MAX_PDF_BYTES,
) {
    // Avoids needless duplicate fetches; the unique index on pdfUrl is what guarantees no duplicates.
    private val arxivLock = Mutex()

    /** Imports the paper named by an arXiv ID or URL in [text], or returns it if already present. */
    suspend fun importArxiv(text: String): Paper {
        val arxivId = ArxivId.parse(text) ?: throw IOException("Not an arXiv ID or URL")
        return importArxiv(arxivId).paper
    }

    /** Imports [arxivId] unless it's already present; [ArxivImport.inserted] tells which. */
    suspend fun importArxiv(arxivId: ArxivId): ArxivImport =
        arxivLock.withLock {
            dao.findByPdfUrl(arxivId.pdfUrl)?.let { return@withLock ArxivImport(it, inserted = false) }
            val entry = arxiv.fetch(arxivId.id)
            val paper = Paper(
                title = entry.title,
                authors = entry.authors,
                abstract = entry.abstract,
                year = entry.year,
                venue = "arXiv",
                pdfUrl = arxivId.pdfUrl,
            )
            val id = dao.insert(paper)
            // -1: another importer (e.g. another activity instance) added it first.
            if (id == -1L) {
                ArxivImport(dao.findByPdfUrl(arxivId.pdfUrl) ?: throw IOException("Couldn't save paper"), inserted = false)
            } else {
                ArxivImport(paper.copy(id = id), inserted = true)
            }
        }

    /**
     * Copies a PDF into the library and adds it, titled after [displayName]. [open] is called
     * once, right away on the IO dispatcher, so short-lived content URI grants are still valid.
     */
    suspend fun importPdf(displayName: String?, open: () -> InputStream?): Paper =
        withContext(Dispatchers.IO) {
            val input = open() ?: throw IOException("Couldn't open file")
            input.use { src ->
                // Check the header before anything touches the disk.
                val header = ByteArray(PDF_MAGIC.size)
                if (src.readFully(header) < header.size || !header.contentEquals(PDF_MAGIC)) {
                    throw IOException("Not a PDF file")
                }
                libraryDir.mkdirs()
                // Prefix must be at least 3 characters for createTempFile.
                val file = File.createTempFile("paper-", ".pdf", libraryDir)
                var inserted = false
                try {
                    file.outputStream().use { out ->
                        out.write(header)
                        copyLimited(src, out, maxPdfBytes - header.size)
                    }
                    val paper = Paper(
                        title = titleFrom(displayName),
                        authors = emptyList(),
                        abstract = "",
                        year = null,
                        venue = null,
                        localFile = file.name,
                    )
                    // Once Room commits, the row must keep its file even if we're cancelled now.
                    val id = withContext(NonCancellable) { dao.insert(paper) }
                    if (id == -1L) throw IOException("Couldn't save paper")
                    inserted = true
                    paper.copy(id = id)
                } finally {
                    if (!inserted) file.delete()
                }
            }
        }

    private suspend fun copyLimited(src: InputStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = src.read(buffer)
            if (n < 0) return
            total += n
            if (total > limit) throw IOException("PDF is too large")
            out.write(buffer, 0, n)
        }
    }

    companion object {
        fun libraryDir(context: Context): File = File(context.filesDir, "papers")

        internal fun titleFrom(displayName: String?): String =
            displayName?.trim()
                ?.replace(Regex("""\.pdf$""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?.ifEmpty { null }
                ?: "Untitled PDF"

        const val MAX_PDF_BYTES = 200L * 1024 * 1024

        private val PDF_MAGIC = "%PDF".toByteArray()

        /** Reads until [buffer] is full or the stream ends; returns the bytes read. */
        private fun InputStream.readFully(buffer: ByteArray): Int {
            var off = 0
            while (off < buffer.size) {
                val n = read(buffer, off, buffer.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }
    }
}
