package dev.sortes.zoomeval

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModelWeights
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MainTest {
    @Test
    fun corpusAndWeightsFilesParse() {
        val dir = Files.createTempDirectory("zoom-eval-test").toFile()
        val corpus =
            File(dir, "corpus.tsv").apply {
                writeText(
                    "# id\tfile\tcategory\ttitle\n1706.03762\t1706.03762.pdf\tsingle-column\tAttention\n\n"
                )
            }
        assertEquals(
            listOf(Paper("1706.03762", "1706.03762.pdf", "single-column", "Attention")),
            readCorpus(corpus),
        )
        val weights =
            File(dir, "w.properties").apply { writeText("beta=0.5\ngamma=0.2\ndelta2=0.03\n") }
        assertEquals(
            CropConfig.DEFAULT.weights.copy(beta = 0.5, gamma = 0.3, delta2 = 0.03),
            readWeights(weights, mapOf("gamma" to "0.3")),
        )
        assertEquals(CropConfig.DEFAULT.weights, readWeights(File(dir, "missing"), emptyMap()))
        assertThrows(IllegalArgumentException::class.java) {
            CropModelWeights().with(mapOf("omega" to "1"))
        }
        dir.deleteRecursively()
    }
}
