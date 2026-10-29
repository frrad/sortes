package dev.sortes.zoomeval

import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.padded
import dev.sortes.app.zoom.regionOn
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a paper's contact sheet: for every page, one thumbnail per method side by side, each
 * showing the padded screen frame, the crop window outlined in its window's colour, the area
 * outside it shaded, ink it cuts off in red and ink a rule ignored in blue.
 */
class ContactSheet(
    private val pdf: MutoolPdf,
    private val aspect: Float,
    private val results: List<ColumnResult>,
    private val title: String,
) {
    private val thumbW = 190
    private val thumbH = (thumbW / aspect).roundToInt()
    private val labelH = 14
    private val gap = 6
    private val pageGap = 18
    private val pagesPerRow = 2
    private val cellW = results.size * (thumbW + gap) - gap
    private val cellH = 16 + thumbH + labelH
    private val headerH = 24 + 16 * results.size + 22

    fun draw(): BufferedImage {
        val n = minOf(pdf.pageCount, MAX_SHEET_PAGES)
        val rows = ceil(n / pagesPerRow.toDouble()).toInt()
        val width = pagesPerRow * cellW + (pagesPerRow - 1) * pageGap + 2 * pageGap
        val height = headerH + rows * (cellH + pageGap) + pageGap
        val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0xEE, 0xEE, 0xEE)
        g.fillRect(0, 0, width, height)
        g.color = Color.BLACK
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
        g.drawString(if (n < pdf.pageCount) "$title (sheet shows the first $n)" else title, pageGap, 20)
        g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        results.forEachIndexed { i, r -> g.drawString(summary(r), pageGap, 40 + 16 * i) }
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.drawString(
            "Outline: window (colour = W1 green, W2 blue, W3 magenta, more: grey). Shaded: off screen. " +
                "Red: ink cut off. Blue: ink ignored by the stamp rule.",
            pageGap, headerH - 8,
        )
        for (p in 0 until n) {
            val x0 = pageGap + (p % pagesPerRow) * (cellW + pageGap)
            val y0 = headerH + (p / pagesPerRow) * (cellH + pageGap)
            g.color = Color.BLACK
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            g.drawString("p${p + 1}", x0, y0 + 12)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 10)
            results.forEachIndexed { i, r ->
                val tx = x0 + i * (thumbW + gap)
                if (p == 0) g.drawString(r.column.label, tx + 28, y0 + 12)
                g.drawImage(thumbnail(p, r), tx, y0 + 16, null)
                val t = r.crops.pages[p]
                g.color = Color.BLACK
                g.drawString(
                    "W${t.windowIndex + 1} cut %.2f%% of page, off %.3f".format(t.pageCut * 100, t.offCentre),
                    tx, y0 + 16 + thumbH + 11,
                )
            }
        }
        g.dispose()
        return sheet
    }

    private fun summary(r: ColumnResult): String {
        val c = r.crops
        return "%-22s zoom %.2fx (scale %.3f)  K=%d  breaks %2d  cut %.3f%% of ink | cost: cut %.3f + off %.3f + page cut %.3f + windows %.3f + switches %.3f = %.3f"
            .format(
                r.column.label, 1 / c.scale, c.scale, c.k, c.breaks, c.cutFraction * 100,
                c.cutTerm, c.offTerm, c.pageCutTerm, c.windowTerm, c.switchTerm, c.total,
            )
    }

    private val pageImages = HashMap<Int, BufferedImage>()

    private fun pageImage(p: Int, size: PageSize, k: Float): BufferedImage = pageImages.getOrPut(p) {
        if (pageImages.size > 2) pageImages.clear() // only the current page's methods share it
        pdf.renderImage(p, max(1, (size.width * k).roundToInt()), max(1, (size.height * k).roundToInt()))
    }

    private fun thumbnail(p: Int, r: ColumnResult): BufferedImage {
        val size = pdf.pageSizes[p]
        val frame = size.padded(aspect)
        val k = thumbW / frame.width
        val padX = (frame.width - size.width) / 2
        val padY = (frame.height - size.height) / 2
        val img = BufferedImage(thumbW, thumbH, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, thumbW, thumbH)
        g.drawImage(pageImage(p, size, k), (padX * k).roundToInt(), (padY * k).roundToInt(), null)

        val t = r.crops.pages[p]
        val window = t.window
        val region = window.regionOn(size, aspect)
        val rx = ((region.left + padX) * k)
        val ry = ((region.top + padY) * k)
        val rw = region.width * k
        val rh = region.height * k

        // Cut and ignored ink, cell by cell: cut is ink outside the cells the window was chosen
        // to cover, exactly as the cost counts it.
        val mask = r.masks[p]
        val rawMask = r.raw[p]
        val cells = t.cells
        val cellW = size.width / mask.width
        val cellH = size.height / mask.height
        val dot = max(1, ceil(cellW * k).toInt())
        for (my in 0 until mask.height) for (mx in 0 until mask.width) {
            val color = when {
                mask[mx, my] && (mx < cells.left || mx >= cells.right || my < cells.top || my >= cells.bottom) -> CUT
                rawMask[mx, my] && !mask[mx, my] -> IGNORED
                else -> continue
            }
            g.color = color
            g.fillRect(((mx * cellW + padX) * k).toInt(), ((my * cellH + padY) * k).toInt(), dot, dot)
        }

        // Shade what's off screen.
        g.color = Color(0, 0, 0, 40)
        g.fillRect(0, 0, thumbW, ry.toInt())
        g.fillRect(0, (ry + rh).toInt(), thumbW, thumbH)
        g.fillRect(0, ry.toInt(), rx.toInt(), (ry + rh).toInt() - ry.toInt())
        g.fillRect((rx + rw).toInt(), ry.toInt(), thumbW, (ry + rh).toInt() - ry.toInt())

        g.color = WINDOW_COLORS.getOrElse(t.windowIndex) { Color.GRAY }
        g.stroke = BasicStroke(2f)
        g.drawRect(rx.roundToInt(), ry.roundToInt(), rw.roundToInt() - 1, rh.roundToInt() - 1)
        g.color = Color.DARK_GRAY
        g.stroke = BasicStroke(1f)
        g.drawRect(0, 0, thumbW - 1, thumbH - 1)
        g.dispose()
        return img
    }

    companion object {
        /** Most pages a sheet shows; the index and TSVs still cover them all. */
        const val MAX_SHEET_PAGES = 80

        private val CUT = Color(0xE0, 0x10, 0x10)
        private val IGNORED = Color(0x10, 0x60, 0xF0)
        private val WINDOW_COLORS = listOf(Color(0x00, 0xA0, 0x00), Color(0x10, 0x50, 0xE0), Color(0xD0, 0x00, 0xD0))
    }
}
