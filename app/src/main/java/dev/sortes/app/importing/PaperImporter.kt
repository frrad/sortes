package dev.sortes.app.importing

import android.content.Context
import dev.sortes.app.arxiv.ArxivClient
import dev.sortes.app.arxiv.ArxivId
import dev.sortes.app.data.Paper
import dev.sortes.app.data.PaperDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Adds papers to the library from arXiv or from PDF files. Failures throw [IOException]. */
class PaperImporter(
    private val dao: PaperDao,
    private val arxiv: ArxivClient,
    private val libraryDir: File,
) {
    // Serializes arXiv imports so the duplicate check can't race with a concurrent insert.
    private val arxivLock = Mutex()

    /** Imports the paper named by an arXiv ID or URL in [text], or returns it if already present. */
    suspend fun importArxiv(text: String): Paper {
        val arxivId = ArxivId.parse(text) ?: throw IOException("Not an arXiv ID or URL")
        return arxivLock.withLock {
            dao.findByPdfUrl(arxivId.pdfUrl)?.let { return@withLock it }
            val entry = arxiv.fetch(arxivId.id)
            val paper = Paper(
                title = entry.title,
                authors = entry.authors,
                abstract = entry.abstract,
                year = entry.year,
                venue = "arXiv",
                pdfUrl = arxivId.pdfUrl,
            )
            paper.copy(id = dao.insert(paper))
        }
    }

    /**
     * Copies a PDF into the library and adds it, titled after [displayName]. [open] is called
     * once, right away on the IO dispatcher, so short-lived content URI grants are still valid.
     */
    suspend fun importPdf(displayName: String?, open: () -> InputStream?): Paper =
        withContext(Dispatchers.IO) {
            libraryDir.mkdirs()
            // Prefix must be at least 3 characters for createTempFile.
            val file = File.createTempFile("paper-", ".pdf", libraryDir)
            try {
                val input = open() ?: throw IOException("Couldn't open file")
                input.use { src -> file.outputStream().use { src.copyTo(it) } }
                if (!isPdf(file)) throw IOException("Not a PDF file")
                val paper = Paper(
                    title = titleFrom(displayName),
                    authors = emptyList(),
                    abstract = "",
                    year = null,
                    venue = null,
                    localFile = file.name,
                )
                paper.copy(id = dao.insert(paper))
            } catch (e: Throwable) {
                file.delete()
                throw e
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

        private fun isPdf(file: File): Boolean {
            val magic = ByteArray(4)
            val n = file.inputStream().use { it.read(magic) }
            return n == 4 && magic.contentEquals("%PDF".toByteArray())
        }
    }
}
