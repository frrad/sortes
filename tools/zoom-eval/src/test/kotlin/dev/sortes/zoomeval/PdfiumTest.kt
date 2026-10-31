package dev.sortes.zoomeval

import dev.sortes.app.zoom.PageSize
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** [PdfiumPdf]'s pure parts, and (where the pinned pdfium is installed) real renders of PDFs written here. */
class PdfiumTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun pageSizesAreWholePointsLikePdfRenderer() {
        // pdfClient's Page::Width() is an int: A4 is 595 x 841 on the tablet.
        assertEquals(PageSize(595f, 841f), PdfiumPdf.wholePoints(595.276, 841.89))
        assertEquals(PageSize(612f, 792f), PdfiumPdf.wholePoints(612.0, 792.0))
    }

    @Test
    fun rgbaRowsBecomeOpaqueArgbSkippingStridePadding() {
        // 2x2, stride 12 (4 bytes of padding a row); alpha is ignored as on an opaque page.
        val bytes = byteArrayOf(
            1, 2, 3, 0, 10, 20, 30, 0x7F, 9, 9, 9, 9,
            -1, 0, 0, -1, 0, 0, -1, -1, 9, 9, 9, 9,
        )
        assertEquals(
            listOf(0xFF010203.toInt(), 0xFF0A141E.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt()),
            Pdfium.rgbaToOpaqueArgb(bytes, 2, 2, 12).toList(),
        )
        assertThrows(IllegalArgumentException::class.java) { Pdfium.rgbaToOpaqueArgb(bytes, 2, 3, 12) }
        assertThrows(IllegalArgumentException::class.java) { Pdfium.rgbaToOpaqueArgb(bytes, 4, 2, 12) }
    }

    @Test
    fun errorCodesAreExplained() {
        assertEquals("not a PDF or corrupted", Pdfium.errorMessage(3))
        assertEquals("password required or incorrect", Pdfium.errorMessage(4))
        assertEquals("error 42", Pdfium.errorMessage(42))
    }

    @Test
    fun theLibraryMustBeThePinnedBuild() {
        val dir = tmp.newFolder()
        val missing = assertThrows(IOException::class.java) { Pdfium.libraryIn(dir) }
        assertTrue(missing.message!!, "setup-pdfium.sh" in missing.message!!)

        File(dir, "lib").mkdirs()
        File(dir, "lib/libpdfium.so").writeText("")
        File(dir, "VERSION").writeText("MAJOR=120\nMINOR=0\nBUILD=6099\nPATCH=0\n")
        val wrong = assertThrows(IOException::class.java) { Pdfium.libraryIn(dir) }
        assertTrue(wrong.message!!, "6099" in wrong.message!! && "setup-pdfium.sh" in wrong.message!!)

        File(dir, "VERSION").writeText("MAJOR=114\nMINOR=0\nBUILD=5731\nPATCH=0\n")
        assertEquals(File(dir, "lib/libpdfium.so"), Pdfium.libraryIn(dir))
    }

    @Test
    fun sortesPdfiumOverridesTheInstallDir() {
        assertEquals(File("/opt/pdfium"), Pdfium.configuredDir(mapOf("SORTES_PDFIUM" to "/opt/pdfium")))
        assertEquals(Pdfium.defaultDir, Pdfium.configuredDir(emptyMap()))
    }

    @Test
    fun rendersPagesAsTheTabletDoes() = runBlocking {
        requirePdfium()
        // An A4 page with a black square 100-200 pt from the top left, and a rotated, cropped page.
        val file = tmp.newFile("doc.pdf").apply {
            writeBytes(pdf(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595.276 841.89] /Contents 4 0 R >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /CropBox [10 20 510 720] /Rotate 90 >>",
                contents = "0 g 100 641.89 100 100 re f",
            ))
        }
        val pdf = PdfiumPdf(file)
        assertEquals(listOf(PageSize(595f, 841f), PageSize(700f, 500f)), pdf.pageSizes)
        assertEquals(2, pdf.pageCount)

        // One pixel per point: the square fills pixels 100-199.
        val px = pdf.renderPixels(0, 595, 841)
        assertEquals(595 * 841, px.size)
        assertEquals(0xFF000000.toInt(), px[150 * 595 + 150])
        assertEquals(-1, px[50 * 595 + 50])
        assertEquals(-1, px[150 * 595 + 250])
        assertTrue(px.all { it ushr 24 == 0xFF })

        // Squashed to any size, as PdfRenderer's scale matrix does.
        val small = pdf.renderPixels(0, 119, 168)
        assertEquals(0xFF000000.toInt(), small[30 * 119 + 30])
        assertEquals(-1, small[10 * 119 + 10])
        val image = pdf.renderImage(0, 119, 168)
        assertEquals(0x000000, image.getRGB(30, 30) and 0xFFFFFF)
        assertEquals(0xFFFFFF, image.getRGB(10, 10) and 0xFFFFFF)

        assertThrows(IllegalArgumentException::class.java) { pdf.argb(2, 10, 10) }
        assertThrows(IllegalArgumentException::class.java) { pdf.argb(0, 0, 10) }
        Unit
    }

    @Test
    fun unreadableFilesSayWhy() {
        requirePdfium()
        val missing = assertThrows(IOException::class.java) { PdfiumPdf(File(tmp.root, "nope.pdf")) }
        assertTrue(missing.message!!, "No such PDF" in missing.message!!)
        val junk = tmp.newFile("junk.pdf").apply { writeText("not a pdf at all") }
        val bad = assertThrows(IOException::class.java) { PdfiumPdf(junk) }
        assertTrue(bad.message!!, "not a PDF or corrupted" in bad.message!!)
    }

    /**
     * Skips unless the pinned pdfium is installed; with SORTES_REQUIRE_PDFIUM set (CI, after
     * setup-pdfium.sh) a missing pdfium fails instead.
     */
    private fun requirePdfium() {
        val installed = runCatching { Pdfium.libraryIn(Pdfium.configuredDir()) }
        if (System.getenv("SORTES_REQUIRE_PDFIUM") != null) installed.getOrThrow()
        assumeTrue("pdfium isn't installed (tools/zoom-eval/setup-pdfium.sh)", installed.isSuccess)
    }

    /** A PDF with [pages] (objects 5…, all children of 2 0 R) and one content stream, object 4. */
    private fun pdf(vararg pages: String, contents: String): ByteArray {
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [${pages.indices.joinToString(" ") { "${it + 5} 0 R" }}] /Count ${pages.size} >>",
            "<< >>",
            "<< /Length ${contents.length} >>\nstream\n$contents\nendstream",
        ) + pages
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objects.mapIndexed { i, body ->
            out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
