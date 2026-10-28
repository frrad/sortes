package dev.sortes.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import dev.sortes.app.zoom.InkMask
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
class PdfPages private constructor(
    private val renderer: PdfRenderer,
    private val pageSizes: List<PageSize>,
) : PageSource, Closeable {

    // PdfRenderer allows only one open page at a time and isn't thread-safe.
    private val mutex = Mutex()
    private var closed = false

    override val pageCount: Int get() = pageSizes.size

    override fun pageSize(index: Int): PageSize = pageSizes[index]

    override suspend fun renderMask(index: Int, width: Int, height: Int): InkMask {
        val matrix = Matrix().apply {
            val size = pageSizes[index]
            setScale(width / size.width, height / size.height)
        }
        val bitmap = render(index, width, height, matrix)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return InkMask.fromArgb(pixels, width, height)
    }

    override suspend fun renderRegion(index: Int, region: Region, width: Int, height: Int): Bitmap =
        render(index, width, height, regionTransform(region, width, height))

    private suspend fun render(index: Int, width: Int, height: Int, transform: Matrix): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "PdfPages is closed" }
                renderer.openPage(index).use { page ->
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // Pages render onto a transparent bitmap; give them a paper background.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

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
            val sizes = try {
                if (renderer.pageCount == 0) throw java.io.IOException("PDF has no pages")
                List(renderer.pageCount) { i ->
                    renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
                }
            } catch (e: Exception) {
                renderer.close()
                throw e
            }
            return PdfPages(renderer, sizes)
        }
    }
}

/** Maps page points to bitmap pixels so that [region] fills a [width] x [height] bitmap. */
internal fun regionTransform(region: Region, width: Int, height: Int): Matrix = Matrix().apply {
    setTranslate(-region.left, -region.top)
    postScale(width / region.width, height / region.height)
}
