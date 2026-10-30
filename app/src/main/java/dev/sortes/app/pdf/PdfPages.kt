package dev.sortes.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.Region
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Renders pages of a PDF to bitmaps. Pages are vector data, so each call renders
 * fresh at the requested size; callers should ask for the size they will display.
 */
class PdfPages private constructor(private val renderer: PdfRenderer) : PageSource, Closeable {

    // PdfRenderer allows only one open page at a time and isn't thread-safe.
    private val mutex = Mutex()
    private var closed = false

    override val pageCount: Int = renderer.pageCount

    // Read as pages are first used, so one unreadable page doesn't stop the others opening.
    private val pageSizes = arrayOfNulls<PageSize>(pageCount)

    override suspend fun pageSize(index: Int): PageSize =
        pageSizes[index] ?: withPage(index) { it.size() }

    override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
        val bitmap = render(index, width, height) { size ->
            Matrix().apply { setScale(width / size.width, height / size.height) }
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels
    }

    override suspend fun renderRegion(index: Int, region: Region, width: Int, height: Int): Bitmap =
        render(index, width, height) { regionTransform(region, width, height) }

    private suspend fun render(
        index: Int,
        width: Int,
        height: Int,
        transform: (PageSize) -> Matrix,
    ): Bitmap = withPage(index) { page ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Pages render onto a transparent bitmap; give them a paper background.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, transform(page.size()), PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap
    }

    private suspend fun <T> withPage(index: Int, block: (PdfRenderer.Page) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "PdfPages is closed" }
                renderer.openPage(index).use(block)
            }
        }

    private fun PdfRenderer.Page.size(): PageSize =
        PageSize(width.toFloat(), height.toFloat()).also { pageSizes[index] = it }

    /** Closes once any in-flight render finishes, so it's safe to call from the UI thread. */
    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    renderer.close()
                }
            }
        }
    }

    companion object {
        /** Opens [file] for rendering. Blocking; call off the main thread. */
        fun open(file: File): PdfPages {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
            if (renderer.pageCount == 0) {
                renderer.close()
                throw java.io.IOException("PDF has no pages")
            }
            return PdfPages(renderer)
        }
    }
}

/** Maps page points to bitmap pixels so that [region] fills a [width] x [height] bitmap. */
internal fun regionTransform(region: Region, width: Int, height: Int): Matrix = Matrix().apply {
    setTranslate(-region.left, -region.top)
    postScale(width / region.width, height / region.height)
}
