package dev.sortes.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.sortes.app.pdf.PageSource
import dev.sortes.app.pdf.regionTransform
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.Region
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.Collections

/**
 * Pages drawn from synthetic ink masks, one mask cell per [POINTS_PER_CELL] page points.
 * Records every region the reader asks to render.
 */
class FakePages(
    private val masks: List<InkMask>,
    /** Pages that can't be read at all, like a page pdfium chokes on. */
    private val broken: Set<Int> = emptySet(),
) : PageSource {

    val rendered: MutableList<Pair<Int, Region>> = Collections.synchronizedList(mutableListOf())
    val masked: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** While set, mask rendering (and so analysis) waits for it to complete. */
    @Volatile
    var maskGate: CompletableDeferred<Unit>? = null

    override val pageCount: Int get() = masks.size

    override suspend fun pageSize(index: Int): PageSize {
        if (index in broken) throw IOException("Can't read page $index")
        return PageSize(masks[index].width * POINTS_PER_CELL, masks[index].height * POINTS_PER_CELL)
    }

    override suspend fun renderMask(index: Int, width: Int, height: Int): InkMask {
        maskGate?.await()
        if (index in broken) throw IOException("Can't render page $index")
        masked += index
        val mask = masks[index]
        val ink = BooleanArray(width * height) {
            mask[(it % width) * mask.width / width, (it / width) * mask.height / height]
        }
        return InkMask(width, height, ink)
    }

    override suspend fun renderRegion(index: Int, region: Region, width: Int, height: Int): Bitmap {
        if (index in broken) throw IOException("Can't render page $index")
        rendered += index to region
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.concat(regionTransform(region, width, height))
        val page = pageSize(index)
        val paint = Paint().apply { color = android.graphics.Color.LTGRAY }
        canvas.drawRect(0f, 0f, page.width, page.height, Paint().apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.GRAY
        })
        val mask = masks[index]
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            if (mask[x, y]) {
                canvas.drawRect(
                    x * POINTS_PER_CELL, y * POINTS_PER_CELL,
                    (x + 1) * POINTS_PER_CELL, (y + 1) * POINTS_PER_CELL, paint,
                )
            }
        }
        return bitmap
    }

    companion object {
        const val POINTS_PER_CELL = 10f
    }
}
