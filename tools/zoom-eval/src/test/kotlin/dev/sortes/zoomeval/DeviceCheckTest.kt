package dev.sortes.zoomeval

import dev.sortes.app.zoom.PageSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DeviceCheckTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pgm(w: Int, h: Int, vararg px: Int, header: String = "P5\n$w $h\n255\n") =
        header.toByteArray() + ByteArray(px.size) { px[it].toByte() }

    @Test
    fun pgmParsesCommentsAndRejectsWhatTheDumpNeverWrites() {
        val p = Pgm.parse(pgm(2, 1, 0, 255, header = "P5\n# from the tablet\n2 1\n255\n"))
        assertEquals(2, p.width)
        assertEquals(255, p.grey(1))
        assertEquals(0xFF000000.toInt(), p.argb(0))
        assertThrows(IOException::class.java) { Pgm.parse(pgm(1, 1, 0, header = "P6\n1 1\n255\n")) }
        assertThrows(IOException::class.java) { Pgm.parse(pgm(1, 1, 0, header = "P5\n1 1\n65535\n")) }
        assertThrows(IOException::class.java) { Pgm.parse(pgm(2, 2, 0, 0, 0)) }
        assertThrows(IOException::class.java) { Pgm.parse("P5\n2".toByteArray()) }
        assertThrows(IOException::class.java) { Pgm.parse(pgm(0, 1)) }
    }

    @Test
    fun dumpPagesReplaysTheTabletsRenders() = runBlocking {
        val dir = tmp.newFolder()
        File(dir, "p2_3x2.pgm").writeBytes(pgm(3, 2, 0, 127, 255, 10, 20, 30))
        val pages = DumpPages(dir, listOf(PageSize(10f, 10f), PageSize(30f, 20f)))
        assertEquals(2, pages.pageCount)
        assertEquals(PageSize(30f, 20f), pages.pageSize(1))
        val px = pages.renderPixels(1, 3, 2)
        assertEquals(listOf(0, 127, 255, 10, 20, 30), px.map { it and 0xFF })
        assertEquals(0xFF7F7F7F.toInt(), px[1])
        val missing = assertThrows(IOException::class.java) { runBlocking { pages.renderPixels(0, 3, 2) } }
        assertEquals(true, "page 1" in missing.message!!)
        // A file under the wrong name for its size is caught, not misread.
        File(dir, "p1_2x2.pgm").writeBytes(pgm(3, 2, 0, 0, 0, 0, 0, 0))
        assertThrows(IOException::class.java) { runBlocking { pages.renderPixels(0, 2, 2) } }
        Unit
    }

    @Test
    fun dumpCropsReadTheScaleAndCells() {
        val f = tmp.newFile("x.tsv").apply {
            writeText("p\tdevice\t1\t0\t0.09\t0.0975\t0.805\t18,21,173,227\t5175\n\np\tdevice\t2\t1\t0.1\t0.2\t0.805\t1,2,3,4\t5175\n")
        }
        assertEquals(listOf(cropKey(0.805f, "18,21,173,227"), cropKey(0.805f, "1,2,3,4")), readDumpCrops(f))
        f.writeText("p\tdevice\t1\n")
        assertThrows(IOException::class.java) { readDumpCrops(f) }
    }

    @Test
    fun renderDiffCountsMaskPixelsAtThePipelinesThreshold() {
        // A 10x10 white page but for three pixels. Paper 255, threshold 249: 250 is paper, 248 ink.
        fun page(vararg g: Int) = IntArray(100) { i -> (0xFF shl 24) or ((if (i < g.size) g[i] else 255) * 0x010101) }
        val d = renderDiff(page(0, 250, 248), page(0, 248, 250), 10, 10)
        assertEquals(2, d.maskXor)
        assertEquals(2, d.deviceInk)
        assertEquals(0.04, d.meanAbsGrey, 1e-9)
        // Colour is compared by luminance, as the dump stores it: pure red is 76.
        assertEquals(76, luma(0xFFFF0000.toInt()))
        assertEquals(0.0, renderDiff(page(76), IntArray(100) { if (it == 0) 0xFFFF0000.toInt() else -1 }, 10, 10).meanAbsGrey, 1e-9)
    }
}
