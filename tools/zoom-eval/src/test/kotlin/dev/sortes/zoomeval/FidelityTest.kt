package dev.sortes.zoomeval

import dev.sortes.app.zoom.PageSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FidelityTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun deviceSizesTruncateLikePdfRenderer() {
        // A4: PdfRenderer reports 595 x 841 (pdfClient's Page::Width() truncates to int).
        assertEquals(listOf(PageSize(595f, 841f)), deviceSizes(listOf(PageSize(595.276f, 841.89f))))
    }

    @Test
    fun dumpPagesReplaysTheDevicesGreyPixels() = runBlocking {
        val dir = tmp.newFolder()
        java.io.File(dir, "p2_3x2.pgm").writeBytes("P5\n3 2\n255\n".toByteArray() + byteArrayOf(0, 127, -1, 10, 20, 30))
        val pages = DumpPages(dir, listOf(PageSize(10f, 10f), PageSize(30f, 20f)))
        val px = pages.renderPixels(1, 3, 2)
        assertEquals(listOf(0, 127, 255, 10, 20, 30), px.map { it and 0xFF })
        assertEquals(0xFF7F7F7F.toInt(), px[1])
        assertThrows(IOException::class.java) { runBlocking { pages.renderPixels(0, 3, 2) } }
        Unit
    }

    @Test
    fun hybridTakesFineRendersFromTheSecondRenderer() = runBlocking {
        val a = Solid(1); val b = Solid(2)
        val h = HybridPages(a, b)
        assertEquals(1, h.renderPixels(0, 4, 4)[0] and 0xFF)
        assertEquals(2, h.renderPixels(0, 16, 16)[0] and 0xFF)
        assertEquals(1, h.renderPixels(0, 4, 4)[0] and 0xFF)
    }

    private class Solid(val v: Int) : dev.sortes.app.zoom.PageRenderer {
        override val pageCount = 1
        override suspend fun pageSize(index: Int) = PageSize(10f, 10f)
        override suspend fun renderPixels(index: Int, width: Int, height: Int) = IntArray(width * height) { v }
    }
}
