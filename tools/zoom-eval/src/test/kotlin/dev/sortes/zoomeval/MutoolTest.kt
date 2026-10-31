package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.PageSize
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MutoolTest {
    @Test
    fun pageSizesPreferTheCropBoxAndFollowRotation() {
        val listing = """
            doc.pdf:
            <page pagenum="1">
            <MediaBox l="0" b="0" r="612" t="792" />
            </page>
            <page pagenum="2">
            <MediaBox l="0" b="0" r="612" t="792" />
            <CropBox l="10" b="20" r="510" t="720" />
            <Rotate v="90" />
            </page>
        """.trimIndent()
        assertEquals(
            listOf(PageSize(612f, 792f), PageSize(700f, 500f)),
            MutoolPdf.parsePageSizes(listing),
        )
    }

    @Test
    fun pnmParsesHeadersWithCommentsAndResamples() {
        val bytes = "P5\n# made by hand\n2 2\n255\n".toByteArray() + byteArrayOf(0, 50, 100, -1)
        val pnm = Pnm.parse(bytes)
        assertEquals(2, pnm.width)
        assertEquals(100, pnm.pixel(0, 1, 0))
        assertEquals(255, pnm.pixel(1, 1, 0))
        val big = pnm.resized(4, 4)
        assertEquals(50, big.pixel(3, 0, 0))
        assertEquals(255, big.pixel(3, 3, 0))
    }

    @Test
    fun corpusAndWeightsFilesParse() {
        val dir = Files.createTempDirectory("zoom-eval-test").toFile()
        val corpus = File(dir, "corpus.tsv").apply {
            writeText("# id\tfile\tcategory\ttitle\n1706.03762\t1706.03762.pdf\tsingle-column\tAttention\n\n")
        }
        assertEquals(listOf(Paper("1706.03762", "1706.03762.pdf", "single-column", "Attention")), readCorpus(corpus))
        val weights = File(dir, "w.properties").apply { writeText("beta=0.5\ngamma=0.2\ndelta2=0.03\n") }
        assertEquals(
            CropConfig.DEFAULT.weights.copy(beta = 0.5, gamma = 0.3, delta2 = 0.03),
            readWeights(weights, mapOf("gamma" to "0.3")),
        )
        assertEquals(CropConfig.DEFAULT.weights, readWeights(File(dir, "missing"), emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { CropModelWeights().with(mapOf("omega" to "1")) }
        dir.deleteRecursively()
    }
}
