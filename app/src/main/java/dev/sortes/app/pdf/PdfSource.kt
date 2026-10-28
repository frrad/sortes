package dev.sortes.app.pdf

import android.content.Context
import dev.sortes.app.data.Paper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Resolves a [Paper] to a local PDF file, downloading and caching it on first use. */
class PdfSource(context: Context) {

    private val cacheDir = File(context.cacheDir, "pdfs")

    suspend fun fetch(paper: Paper): File = withContext(Dispatchers.IO) {
        val url = paper.pdfUrl ?: throw IOException("No PDF for this paper")
        val file = File(cacheDir, "${paper.id}.pdf")
        if (!file.exists()) {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, "${paper.id}.pdf.part")
            download(url, tmp)
            if (!tmp.renameTo(file)) throw IOException("Could not save PDF")
        }
        file
    }

    private fun download(url: String, dest: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Download failed: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }
}
