package dev.sortes.app.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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
class PdfPages private constructor(private val renderer: PdfRenderer) : Closeable {

    // PdfRenderer allows only one open page at a time and isn't thread-safe.
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Renders page [index] as large as fits within [maxWidth] x [maxHeight] pixels. */
    suspend fun render(index: Int, maxWidth: Int, maxHeight: Int): Bitmap =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed) { "PdfPages is closed" }
                renderer.openPage(index).use { page ->
                    val scale = minOf(
                        maxWidth.toFloat() / page.width,
                        maxHeight.toFloat() / page.height,
                    )
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    // Pages render onto a transparent bitmap; give them a paper background.
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    /** Closes once any in-flight render finishes, so it's safe to call from the UI thread. */
    override fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                closed = true
                renderer.close()
            }
        }
    }

    companion object {
        suspend fun open(file: File): PdfPages = withContext(Dispatchers.IO) {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfPages(PdfRenderer(fd))
        }
    }
}
