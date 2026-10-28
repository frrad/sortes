package dev.sortes.app.pdf

import android.content.Context
import dev.sortes.app.data.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Resolves a [Paper] to a local PDF file, downloading and caching it on first use. */
class PdfSource(private val cacheDir: File) {

    constructor(context: Context) : this(File(context.cacheDir, "pdfs"))

    suspend fun fetch(paper: Paper): File = withContext(Dispatchers.IO) {
        val url = paper.pdfUrl ?: throw IOException("No PDF for this paper")
        val file = File(cacheDir, "${paper.id}.pdf")
        if (!file.exists()) {
            cacheDir.mkdirs()
            // Unique temp file so overlapping downloads of the same paper can't clobber each other.
            val tmp = File.createTempFile("pdf-${paper.id}-", ".part", cacheDir)
            try {
                download(url, tmp)
                if (!tmp.renameTo(file) && !file.exists()) throw IOException("Could not save PDF")
            } finally {
                tmp.delete()
            }
        }
        file
    }

    /** Drops a cached file, e.g. one that turned out not to be a readable PDF. */
    fun evict(paper: Paper) {
        File(cacheDir, "${paper.id}.pdf").delete()
    }

    private suspend fun download(url: String, dest: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Download failed: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        // Guard against HTML error/captive-portal pages served with a 200.
        val magic = ByteArray(4).also { bytes -> dest.inputStream().use { it.read(bytes) } }
        if (!magic.contentEquals("%PDF".toByteArray())) throw IOException("Response was not a PDF")
    }
}
