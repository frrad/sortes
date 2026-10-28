package dev.sortes.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sortes.app.ui.theme.SortesTheme
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.TestPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h480dp")
class PageReaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Text block at (100, 100)-(500, 700) points on a 600x800 page, page number below it.
    private val pages = FakePages(List(3) { TestPage(60, 80).rect(10, 10, 50, 70).rect(29, 76, 31, 78).mask() })
    private val saves = mutableListOf<Float?>()
    private var saved by mutableStateOf<Float?>(null)

    private fun show(savedScale: Float? = null) {
        saved = savedScale
        composeRule.setContent {
            SortesTheme {
                PageReader(pages, saved, onSaveScale = { saves += it; saved = it })
            }
        }
        awaitRender()
    }

    /** Waits for the reader to have rendered [count] pages in total. */
    private fun awaitRender(count: Int = 1) {
        // Analysis runs off the main thread, so idle the UI repeatedly until it catches up.
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            composeRule.waitForIdle()
            if (pages.rendered.size >= count) break
            check(System.currentTimeMillis() < deadline) { "Rendered only ${pages.rendered}" }
            Thread.sleep(10)
        }
    }

    private fun lastRendered(): Pair<Int, Region> = pages.rendered.last()

    private fun Region.contains(left: Float, top: Float, right: Float, bottom: Float) =
        this.left <= left && this.top <= top &&
            this.left + width >= right && this.top + height >= bottom

    @Test
    fun opensOnTheFirstPageZoomedToTheText() {
        show()

        val (index, region) = lastRendered()
        assertEquals(0, index)
        assertTrue("$region", region.contains(100f, 100f, 500f, 700f))
        // Tight on the text: the page number below it is cropped off.
        assertTrue("$region", region.top + region.height < 760f)
        composeRule.onNodeWithText("1 / 3").assertExists()
    }

    @Test
    fun usesTheSavedZoom() {
        show(savedScale = 0.9f)

        val region = lastRendered().second
        // The whole padded page is 800 points tall here or more; 0.9 of its larger dimension.
        val fullHeight = region.height / 0.9f
        assertTrue("$region", fullHeight >= 800f)
        assertTrue("$region", region.contains(100f, 100f, 500f, 760f))
    }

    @Test
    fun tapsTurnPagesWithTheSameCrop() {
        show()
        val first = lastRendered().second

        composeRule.onRoot().performTouchInput { click(centerRight - Offset(10f, 0f)) }
        awaitRender(2)

        assertEquals(1 to first, lastRendered())
        composeRule.onNodeWithText("2 / 3").assertExists()

        composeRule.onRoot().performTouchInput { click(centerLeft + Offset(10f, 0f)) }
        awaitRender(3)
        assertEquals(0, lastRendered().first)
    }

    @Test
    fun bottomMiddleOpensAndClosesTheZoomPanel() {
        show()
        composeRule.onNodeWithTag("zoomPanel").assertDoesNotExist()

        composeRule.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 5f)) }

        composeRule.onNodeWithTag("zoomPanel").assertExists()
        composeRule.onNodeWithTag("lostInkCurve").assertExists()
        composeRule.onNodeWithText("Auto ✓").assertIsNotEnabled()
        // The page didn't turn.
        composeRule.onNodeWithText("1 / 3").assertExists()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithTag("zoomPanel").assertDoesNotExist()
    }

    @Test
    fun tapsOnThePanelDoNotTurnThePage() {
        show()
        composeRule.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 5f)) }

        composeRule.onNodeWithTag("lostInkCurve").performTouchInput { click(centerRight) }
        composeRule.onNodeWithTag("lostInkCurve").performTouchInput { click(centerLeft) }

        composeRule.onNodeWithText("1 / 3").assertExists()
        assertEquals(1, pages.rendered.size)
    }

    @Test
    fun sliderSavesTheChosenZoomAndRerenders() {
        show()
        val auto = lastRendered().second
        composeRule.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 5f)) }

        // Slide all the way left: the whole page.
        composeRule.onNodeWithTag("zoomSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        awaitRender(2)

        assertEquals(listOf<Float?>(1f), saves)
        val whole = lastRendered().second
        assertTrue("$whole vs $auto", whole.width > auto.width)
        assertTrue("$whole", whole.contains(0f, 0f, 600f, 800f))
        composeRule.onNodeWithText("Zoom 1.00× · nothing cut off").assertExists()
    }

    @Test
    fun autoButtonForgetsTheSavedZoom() {
        show(savedScale = 1f)
        composeRule.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 5f)) }

        composeRule.onNodeWithText("Auto").performClick()
        awaitRender(2)

        assertEquals(listOf<Float?>(null), saves)
        composeRule.onNodeWithText("Auto ✓").assertExists()
        val region = lastRendered().second
        assertTrue("$region", region.top + region.height < 760f)
    }

    @Test
    fun zoomPanelScreenshot() {
        show()
        composeRule.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 5f)) }

        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/reader_zoom_panel.png")
    }
}
