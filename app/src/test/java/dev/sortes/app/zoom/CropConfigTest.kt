package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CropConfigTest {
    private fun body() = TestPage(100, 140).rect(15, 15, 85, 125)

    @Test
    fun theConfigIgnoresMarksAndFurniture() {
        val page = body().rect(2, 2, 4, 3).rect(49, 132, 51, 135).mask()
        val prepared = CropConfig.DEFAULT.prepare(List(4) { page })
        assertTrue(prepared.all { it.inkCount == 70 * 110 })
        // Without the rules, both are kept.
        val bare = CropConfig.DEFAULT.copy(pageRules = emptyList(), furniture = false)
        assertTrue(bare.prepare(listOf(page)).single().inkCount > 70 * 110)
    }

    @Test
    fun theConfigUsesBlocksTheCostBasedZoomAndTheTextModel() {
        val w = CropConfig.DEFAULT.weights
        assertEquals(CropModelWeights(beta = 0.5, epsilon = 0.0, gamma = 0.1, delta = 0.005, kappa = 0.2, splitInk = 0.005, zeta = 0.5, autoMargin = 0.01), w)
        // As labelled in round 5 (labels/ROUND-5.md), with the component weight re-fitted on pdfium's
        // renders (tools/zoom-eval/notes/pdfium-refit.md).
        val t = CropConfig.DEFAULT.text
        assertEquals(TextWeights(comfortable = 28.0, power = 16.0, floor = 0.1, elements = 0.016, lines = 0.1, factor = 4), t)
        assertEquals("tuned+text", CropConfig.DEFAULT.name)
    }

    @Test
    fun withoutFurnitureThePaperRuleChangesNothing() {
        val page = body().mask()
        assertSame(page, CropConfig.DEFAULT.copy(furniture = false).paperRule(listOf(page)).strip(page))
    }

    @Test
    fun configsAreFoundByName() {
        assertSame(CropConfig.DEFAULT, CropConfig.named("tuned+text"))
        assertThrows(IllegalArgumentException::class.java) { CropConfig.named("reader") }
    }

    @Test
    fun textParametersAreRecordedForLabels() {
        val p = CropConfig.DEFAULT.text.parameters()
        assertEquals(28.0, p.getValue("textComfortablePx"), 0.0)
        assertEquals(0.1, p.getValue("lineWeight"), 0.0)
        assertEquals(0.6, p.getValue("minScale"), 1e-6)
        assertEquals(0.97, p.getValue("maxScale"), 1e-6)
    }
}
