package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CropConfigTest {
    private fun body() = TestPage(100, 140).rect(15, 15, 85, 125)

    @Test
    fun theReaderConfigIsTodaysReader() {
        val r = CropConfig.READER
        assertEquals(IGNORABLE_INK_RULES, r.pageRules)
        assertEquals(false, r.furniture)
        assertEquals(CropModelWeights(), r.weights)
    }

    @Test
    fun theTunedConfigIgnoresMarksAndFurniture() {
        val page = body().rect(2, 2, 4, 3).rect(49, 132, 51, 135).mask()
        val prepared = CropConfig.TUNED.prepare(List(4) { page })
        assertTrue(prepared.all { it.inkCount == 70 * 110 })
        // The reader keeps both.
        assertTrue(CropConfig.READER.prepare(listOf(page)).single().inkCount > 70 * 110)
    }

    @Test
    fun withoutFurnitureThePaperRuleChangesNothing() {
        val page = body().mask()
        assertSame(page, CropConfig.READER.paperRule(listOf(page)).strip(page))
    }

    @Test
    fun configsAreFoundByName() {
        assertSame(CropConfig.TUNED, CropConfig.named("tuned"))
        assertSame(CropConfig.READER, CropConfig.named("reader"))
        assertThrows(IllegalArgumentException::class.java) { CropConfig.named("nope") }
    }
}
