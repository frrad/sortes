package dev.sortes.app.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnorableInkTest {

    /** Clears one column of the mask, recording the order rules ran in. */
    private class ClearColumn(val x: Int, val log: MutableList<Int>) : IgnorableInkRule {
        override fun strip(mask: InkMask): InkMask {
            log += x
            return InkMask(mask.width, mask.height, BooleanArray(mask.width * mask.height) {
                it % mask.width != x && mask[it % mask.width, it / mask.width]
            })
        }
    }

    @Test
    fun rulesRunInOrderOnEachOthersOutput() {
        val log = mutableListOf<Int>()
        val mask = TestPage(4, 2).rect(0, 0, 4, 2).mask()

        val result = mask.withoutIgnorableInk(listOf(ClearColumn(1, log), ClearColumn(3, log)))

        assertEquals(listOf(1, 3), log)
        assertEquals(4, result.inkCount)
        assertTrue(result[0, 0] && !result[1, 0] && result[2, 1] && !result[3, 1])
    }

    @Test
    fun noRulesLeaveTheMaskAlone() {
        val mask = textPage().mask()

        assertSame(mask, mask.withoutIgnorableInk(emptyList()))
    }

    @Test
    fun theReaderIgnoresMarginStamps() {
        assertTrue(MarginStampRule in IGNORABLE_INK_RULES)
        val stamped = maskFixture("attention-p1.txt")

        assertTrue(stamped.withoutIgnorableInk().inkCount < stamped.inkCount)
    }
}
