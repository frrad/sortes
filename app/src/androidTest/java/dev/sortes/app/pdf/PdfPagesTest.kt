package dev.sortes.app.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.analyzePaper
import dev.sortes.app.zoom.regionOn
import dev.sortes.app.zoom.renderMask
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the real PdfRenderer, on a PDF drawn by the test. */
@RunWith(AndroidJUnit4::class)
class PdfPagesTest {

    private lateinit var file: File
    private lateinit var pages: PdfPages

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "pdf-pages-test.pdf")
        writePdf(file)
        pages = PdfPages.open(file)
    }

    @After
    fun tearDown() {
        pages.close()
        file.delete()
    }

    @Test
    fun readsPageSizesInPoints() = runBlocking {
        assertEquals(2, pages.pageCount)
        assertEquals(PageSize(600f, 800f), pages.pageSize(0))
        assertEquals(PageSize(800f, 600f), pages.pageSize(1))
    }

    @Test
    fun masksMarkWhereTheInkIs() = runBlocking {
        // 60x80 cells: 10 points per cell, so the block covers cells (10, 10) to (50, 70).
        val mask = pages.renderMask(0, 60, 80)

        assertTrue(mask[30, 40])
        assertTrue(mask[11, 11])
        assertTrue(mask[48, 68])
        assertFalse(mask[5, 5])
        assertFalse(mask[55, 75])
        // Anti-aliasing may tip the cells along the block's edges either way.
        assertTrue("${mask.inkCount}", kotlin.math.abs(mask.inkCount - 40 * 60) <= 2 * (40 + 60))
    }

    @Test
    fun rendersTheRequestedRegionToFillTheBitmap() = runBlocking {
        // Exactly the black block: every pixel should be ink.
        val inside = pages.renderRegion(0, Region(100f, 100f, 400f, 600f), 300, 450)
        assertEquals(300, inside.width)
        assertEquals(450, inside.height)
        for ((x, y) in listOf(2 to 2, 150 to 225, 297 to 447)) {
            assertEquals("($x, $y)", Color.BLACK, inside.getPixel(x, y))
        }

        // Shifted half a block right and down: the top-left quarter is ink, the rest paper.
        val shifted = pages.renderRegion(0, Region(300f, 400f, 400f, 600f), 300, 450)
        assertEquals(Color.BLACK, shifted.getPixel(50, 50))
        assertEquals(Color.WHITE, shifted.getPixel(250, 50))
        assertEquals(Color.WHITE, shifted.getPixel(50, 400))
    }

    @Test
    fun paddingOutsideThePageRendersWhite() = runBlocking {
        val bitmap =
            pages.renderRegion(
                0,
                CropWindow(0f, 0f, 1f).regionOn(pages.pageSize(0), 0.5f),
                200,
                400,
            )

        // On a 1:2 screen the 600x800 page is padded to 600x1200: white above and below.
        assertEquals(Color.WHITE, bitmap.getPixel(100, 10))
        assertEquals(Color.WHITE, bitmap.getPixel(100, 390))
        assertEquals(Color.BLACK, bitmap.getPixel(100, 200))
    }

    @Test
    fun analysisZoomsToTheBlocks() = runBlocking {
        val paper = analyzePaper(pages, 0.75f)
        val scale = paper.auto.scale

        // Page 0's 400x600 block is 3:4 and fills 0.75 of the page's height.
        assertTrue("auto $scale", scale in 0.74f..0.8f)
        val window = paper.place(0, paper.auto).window
        val region = window.regionOn(pages.pageSize(0), 0.75f)
        assertTrue("$region", region.left <= 102f && region.left + region.width >= 498f)
        assertTrue("$region", region.top <= 102f && region.top + region.height >= 698f)
    }

    private fun writePdf(file: File) {
        val doc = PdfDocument()
        val black = Paint().apply { color = Color.BLACK }
        doc.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create()).also {
            it.canvas.drawRect(100f, 100f, 500f, 700f, black)
            doc.finishPage(it)
        }
        doc.startPage(PdfDocument.PageInfo.Builder(800, 600, 2).create()).also {
            it.canvas.drawRect(300f, 200f, 500f, 400f, black)
            doc.finishPage(it)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
