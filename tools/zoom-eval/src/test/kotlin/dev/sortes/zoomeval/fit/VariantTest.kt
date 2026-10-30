package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VariantTest {
    @Test
    fun variantsAreTheSharedConfigs() {
        // The fit's "furniture+marks" is the app's CropConfig.TUNED rules, and "reader" its READER.
        val tuned = Variant.of(CropConfig.TUNED).config(CropConfig.TUNED.weights)
        assertEquals(CropConfig.TUNED.copy(name = tuned.name), tuned)
        assertEquals(CropConfig.READER.copy(name = "reader"), Variant().config())
    }

    @Test
    fun namesAsLabelsRecordThem() {
        assertEquals(Variant(), Variant.parse("reader"))
        assertEquals(Variant(), Variant.parse("reader+weights"))
        assertEquals(Variant(furniture = true, marks = true), Variant.parse("tuned"))
        assertEquals(Variant(furniture = true, marks = true), Variant.parse("tuned+weights"))
        assertEquals(Variant(furniture = true, marks = true), Variant.parse("reader+furniture+marks"))
        assertEquals(Variant(furniture = true), Variant.parse("furniture"))
        assertThrows(IllegalArgumentException::class.java) { Variant.parse("sparkles") }
    }
}
