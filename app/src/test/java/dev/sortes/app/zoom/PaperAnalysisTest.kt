package dev.sortes.app.zoom

import dev.sortes.app.ui.FakePages
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

// FakePages draws with android.graphics, hence Robolectric.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperAnalysisTest {

    private val aspect = 0.75f

    @Test
    fun samplesEveryPageOfShortPapers() {
        assertEquals(listOf(0, 1, 2), samplePages(3, 60))
        assertEquals(List(60) { it }, samplePages(60, 60))
    }

    @Test
    fun samplesLongPapersEvenlyIncludingBothEnds() {
        val sample = samplePages(300, 60)

        assertEquals(60, sample.size)
        assertEquals(0, sample.first())
        assertEquals(299, sample.last())
        assertEquals(sample.sorted().distinct(), sample)
        val gaps = sample.zipWithNext { a, b -> b - a }
        assertTrue("$gaps", gaps.all { it in 4..6 })
    }

    @Test
    fun samplingEdgeCases() {
        assertEquals(listOf(0), samplePages(10, 1))
        assertEquals(emptyList<Int>(), samplePages(0, 5))
        assertThrows(IllegalArgumentException::class.java) { samplePages(10, 0) }
    }

    @Test
    fun longPapersRenderOnlyTheSample() {
        val pages = FakePages(List(300) { textPage().mask() })

        val paper = runBlocking { analyzePaper(pages, aspect) }

        assertEquals(MAX_ANALYZED_PAGES, pages.masked.size)
        assertEquals(MAX_ANALYZED_PAGES, paper.crop.pagesAnalyzed)
        assertEquals(300, paper.pageCount)
        assertTrue("auto ${paper.crop.autoScale}", paper.crop.autoScale in 0.75f..0.80f)
    }

    @Test
    fun windowsCanBePlacedOnPagesOutsideTheSample() {
        // Page 1 isn't sampled (0, 3, 6, ...) but still gets a window that fits its text.
        val pages = FakePages(List(10) { textPage().mask() })
        val paper = runBlocking { analyzePaper(pages, aspect, maxPages = 4) }
        assertTrue(1 !in pages.masked)

        val window = runBlocking {
            val scale = paper.crop.autoScale
            paper.window(pages, 1, scale, paper.crop.anchor(scale))
        }

        assertTrue("$window", window.covers(60, 80, aspect, 10, 10, 50, 70))
    }

    @Test
    fun unreadablePagesAreLeftOut() {
        val pages = FakePages(List(5) { textPage().mask() }, broken = setOf(1, 3))

        val paper = runBlocking { analyzePaper(pages, aspect) }

        assertEquals(3, paper.crop.pagesAnalyzed)
        assertTrue("auto ${paper.crop.autoScale}", paper.crop.autoScale in 0.75f..0.80f)
    }

    @Test
    fun failsWhenNoPageCanBeRead() {
        val pages = FakePages(List(2) { textPage().mask() }, broken = setOf(0, 1))

        assertThrows(IOException::class.java) { runBlocking { analyzePaper(pages, aspect) } }
    }
}
