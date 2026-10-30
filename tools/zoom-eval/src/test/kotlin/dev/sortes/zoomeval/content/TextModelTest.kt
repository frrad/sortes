package dev.sortes.zoomeval.content

import dev.sortes.app.zoom.CropConfig
import dev.sortes.app.zoom.CropModel
import dev.sortes.app.zoom.InkMask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TextModelTest {
    /** A page of "text": 3x[glyph] glyphs on lines every [pitch] cells, inside the margins. */
    private fun page(w: Int, h: Int, glyph: Int, pitch: Int, margin: Int): InkMask {
        val ink = BooleanArray(w * h)
        var top = margin
        while (top + glyph < h - margin) {
            for (x in margin until w - margin) if ((x - margin) % 5 < 3) for (y in top until top + glyph) ink[y * w + x] = true
            top += pitch
        }
        return InkMask(w, h, ink)
    }

    @Test fun `without measurable text it takes TUNED's zoom`() = runBlocking {
        val coarse = List(3) { page(48, 64, 1, 3, 8) }
        val fine = List(3) { InkMask(48 * 4, 64 * 4, BooleanArray(48 * 4 * 64 * 4)) }
        val m = TextModel(elements = 0.0, lines = 0.0)
        val expected = CropModel(0.75f, CropConfig.TUNED.weights).auto(CropConfig.TUNED.prepare(coarse)).scale
        assertEquals(expected, m.crops(coarse, fine, 0.75f).scale, 1e-6f)
    }

    @Test fun `bigger text on the same layout never zooms tighter`() = runBlocking {
        val coarse = List(3) { page(48, 64, 1, 3, 8) }
        fun fine(glyph: Int) = List(3) { page(48 * 4, 64 * 4, glyph, glyph * 2, 32) }
        val m = TextModel(elements = 0.0, lines = 0.0)
        val small = m.crops(coarse, fine(3), 0.75f).scale
        val big = m.crops(coarse, fine(12), 0.75f).scale
        assertTrue("small $small big $big", big >= small)
    }

    @Test fun `parameters are recorded`() {
        val p = TextModel().parameters()
        assertEquals(28.0, p.getValue("textComfortablePx"), 0.0)
        assertEquals(0.1, p.getValue("lineWeight"), 0.0)
    }
}
