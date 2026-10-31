package dev.sortes.zoomeval

import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.renderMask
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoPagesTest {
    /** Grey pages with anti-aliased edges, counting renders. */
    private class Grey : PageRenderer {
        var renders = 0
        override val pageCount = 3
        override suspend fun pageSize(index: Int) = PageSize(600f, 800f)
        override suspend fun renderPixels(index: Int, width: Int, height: Int): IntArray {
            renders++
            return IntArray(width * height) { i ->
                val x = i % width
                val y = i / width
                val g = when {
                    x in width / 6 until width * 5 / 6 && y % 7 < 3 -> 40
                    x == width / 6 - 1 -> 200 + index // a faint edge, just ink
                    else -> 255
                }
                (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
    }

    private fun InkMask.cells() = (0 until height).flatMap { y -> (0 until width).map { x -> this[x, y] } }

    @Test
    fun rememberedPagesThresholdBackToTheSameMasks() = runBlocking {
        val grey = Grey()
        val memo = MemoPages(grey)
        for (i in 0 until 3) {
            val direct = grey.renderMask(i, 60, 80)
            assertEquals(direct.cells(), memo.renderMask(i, 60, 80).cells())
            assertEquals(direct.cells(), memo.renderMask(i, 60, 80).cells())
        }
        // Three direct renders, three through the memo: each page once.
        assertEquals(6, grey.renders)
    }

    @Test
    fun rendersBiggerThanTheLimitAreNotRemembered() = runBlocking {
        val grey = Grey()
        val memo = MemoPages(grey, maxPixels = 60 * 80)
        memo.renderMask(0, 240, 320)
        memo.renderMask(0, 240, 320)
        memo.renderMask(0, 60, 80)
        memo.renderMask(0, 60, 80)
        assertEquals(3, grey.renders)
    }
}
