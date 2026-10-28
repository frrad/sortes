package dev.sortes.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sortes.app.ui.theme.SortesTheme
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.TestPage
import dev.sortes.app.zoom.maskFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h480dp")
class PageReaderTest {

    // By default Compose tests run effects on an unconfined dispatcher, so after the reader's
    // withContext(Dispatchers.Default) its produceState writes would happen on the worker thread.
    // A standard test dispatcher queues them to run on the main thread instead, as in the app;
    // [idle] runs the queue.
    private val effects = StandardTestDispatcher()

    @OptIn(ExperimentalTestApi::class)
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>(effects)

    private val offMainWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private lateinit var writeObserver: ObserverHandle

    @Before
    fun watchForOffMainWrites() {
        writeObserver = Snapshot.registerGlobalWriteObserver {
            if (Looper.myLooper() != Looper.getMainLooper()) offMainWrites += Thread.currentThread().name
        }
    }

    // Regression: state written from a worker thread wakes Compose's global snapshot flusher
    // from that thread; if the test ends just then, Robolectric's looper reset drops the wake-up
    // and every later Compose test in the JVM hangs waiting for idle.
    @After
    fun noOffMainWrites() {
        writeObserver.dispose()
        assertEquals("Compose state written off the main thread", emptyList<String>(), offMainWrites.toList())
    }

    // Text block at (100, 100)-(500, 700) points on a 600x800 page, page number below it.
    private var pages = FakePages(List(3) { TestPage(60, 80).rect(10, 10, 50, 70).rect(29, 76, 31, 78).mask() })
    private val saves = mutableListOf<Float?>()
    private var saved by mutableStateOf<Float?>(null)
    private var closes = 0
    private var readerSize by mutableStateOf<Pair<Dp, Dp>?>(null)

    /** Shows the reader as the app does: inside [ReaderFrame], with its back arrow. */
    private fun show(savedScale: Float? = null) {
        saved = savedScale
        composeRule.setContent {
            SortesTheme {
                ReaderFrame(onClose = { closes++ }) {
                    val size = readerSize
                    Box(if (size == null) Modifier else Modifier.size(size.first, size.second)) {
                        PageReader(pages, saved, onSaveScale = { saves += it; saved = it })
                    }
                }
            }
        }
    }

    /** Runs queued effects and waits for Compose to settle. */
    private fun idle() {
        repeat(2) {
            effects.scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    /** Shows the reader and waits for the zoomed first page. */
    private fun showZoomed(savedScale: Float? = null) {
        show(savedScale)
        awaitRendered { (index, region) -> index == 0 && region.isZoomed() }
    }

    /**
     * Renders of the page to read: the size of the reader. The whole-page preview above the open
     * zoom panel renders smaller.
     */
    private fun readerRenders(): List<Pair<Int, Region>> {
        val height = readerSize?.let { with(composeRule.density) { it.second.roundToPx() } }
            ?: composeRule.onRoot().fetchSemanticsNode().size.height
        return pages.renders.toList().filter { it.height == height }.map { it.index to it.region }
    }

    /** Waits until the latest reader render satisfies [done]. Analysis runs off the main thread. */
    private fun awaitRendered(done: (Pair<Int, Region>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            val last = readerRenders().lastOrNull()
            if (last != null && done(last)) break
            check(System.currentTimeMillis() < deadline) { "Rendered only ${pages.renders}" }
            Thread.sleep(10)
        }
        idle()
    }

    private fun lastRendered(): Pair<Int, Region> = readerRenders().last()

    /** Narrower than the whole padded page, which is at least the page's 600 points wide. */
    private fun Region.isZoomed() = width < 590f

    private fun Region.contains(left: Float, top: Float, right: Float, bottom: Float) =
        this.left <= left && this.top <= top &&
            this.left + width >= right && this.top + height >= bottom

    private fun tapZoomZone() {
        composeRule.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 5f)) }
    }

    private fun tapForward() {
        composeRule.onRoot().performTouchInput { click(centerRight - Offset(10f, 0f)) }
    }

    /** Opens the zoom panel, retrying while the analysis it needs is still running. */
    private fun openPanel() {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            tapZoomZone()
            idle()
            if (composeRule.onAllNodesWithTag("zoomPanel").fetchSemanticsNodes().isNotEmpty()) break
            check(System.currentTimeMillis() < deadline) { "Zoom panel never opened" }
            Thread.sleep(10)
        }
    }

    @Test
    fun opensOnTheFirstPageZoomedToTheText() {
        showZoomed()

        val (index, region) = lastRendered()
        assertEquals(0, index)
        assertTrue("$region", region.contains(100f, 100f, 500f, 700f))
        // Tight on the text: the page number below it is cropped off.
        assertTrue("$region", region.top + region.height < 760f)
    }

    @Test
    fun noPageCounterOnThePage() {
        showZoomed()

        composeRule.onNodeWithText("1 / 3").assertDoesNotExist()
    }

    @Test
    fun panelShowsThePageNumber() {
        showZoomed()
        tapForward()
        awaitRendered { it.first == 1 }

        openPanel()

        composeRule.onNodeWithText("2 / 3").assertExists()
    }

    @Test
    fun showsWholePagesWhileAnalyzing() {
        val gate = CompletableDeferred<Unit>()
        pages.maskGate = gate
        show()
        awaitRendered { it.first == 0 }

        assertTrue("${lastRendered()}", lastRendered().second.contains(0f, 0f, 600f, 800f))
        // Pages still turn, and render whole.
        tapForward()
        awaitRendered { it.first == 1 }
        assertTrue("${lastRendered()}", lastRendered().second.contains(0f, 0f, 600f, 800f))
        // There's no curve yet, so the zoom controls don't open...
        tapZoomZone()
        composeRule.onNodeWithTag("zoomPanel").assertDoesNotExist()

        gate.complete(Unit)

        // ...nor pop up later; the page zooms in once the analysis is done.
        awaitRendered { (index, region) -> index == 1 && region.isZoomed() }
        composeRule.onNodeWithTag("zoomPanel").assertDoesNotExist()
    }

    @Test
    fun usesTheSavedZoom() {
        show(savedScale = 0.9f)
        // 0.9 of the padded page, which is 600 x 800 points or more.
        awaitRendered { (_, region) -> region.width in 535f..600f * 0.95f }

        val region = lastRendered().second
        assertTrue("$region", region.contains(100f, 100f, 500f, 760f))
    }

    @Test
    fun tapsTurnPagesWithTheSameCrop() {
        showZoomed()
        val first = lastRendered().second

        tapForward()
        awaitRendered { it.first == 1 }
        assertEquals(1 to first, lastRendered())

        composeRule.onRoot().performTouchInput { click(centerLeft + Offset(10f, 0f)) }
        awaitRendered { it.first == 0 }
        assertEquals(0 to first, lastRendered())
    }

    @Test
    fun bottomMiddleOpensAndClosesTheZoomPanel() {
        showZoomed()
        val renders = readerRenders().size
        composeRule.onNodeWithTag("zoomPanel").assertDoesNotExist()

        openPanel()

        composeRule.onNodeWithTag("lostInkCurve").assertExists()
        composeRule.onNodeWithText("Auto ✓").assertIsNotEnabled()
        composeRule.onNodeWithText("1 / 3").assertExists()
        // The page didn't turn.
        assertEquals(renders, readerRenders().size)

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithTag("zoomPanel").assertDoesNotExist()
    }

    @Test
    fun openPanelShowsTheWholePageWithTheCropFramed() {
        showZoomed()
        val reading = readerRenders()
        val crop = reading.last().second

        openPanel()
        awaitPreview()

        // Above the panel, not under it.
        val preview = composeRule.onNodeWithTag("cropPreview").fetchSemanticsNode().boundsInWindow
        val panel = composeRule.onNodeWithTag("zoomPanel").fetchSemanticsNode().boundsInWindow
        assertTrue("preview $preview overlaps panel $panel", preview.bottom <= panel.top)
        // The page is rendered whole for the preview, at the preview's size...
        val whole = pages.renders.last()
        assertEquals(0, whole.index)
        assertTrue("${whole.region}", whole.region.contains(0f, 0f, 600f, 800f))
        assertEquals(preview.width.roundToInt() to preview.height.roundToInt(), whole.width to whole.height)
        // ...the crop being read isn't changed or re-rendered, and the analysis isn't redone...
        assertEquals(reading, readerRenders())
        // ...and the frame is that crop, mapped onto the whole page.
        assertRect(crop.within(whole.region, preview), "cropFrame")
        // The page number below the text is cut off, and boxed (to the analysis' resolution).
        val cut = composeRule.onAllNodesWithTag("cutInk").fetchSemanticsNodes().map { it.boundsInWindow }
        val number = Region(290f, 760f, 20f, 20f).within(whole.region, preview)
        assertEquals("$cut", 1, cut.size)
        assertTrue("${cut.single()} vs $number", cut.single().overlaps(number))
        assertTrue("${cut.single()} vs $number", cut.single().top >= number.top - 5f)

        composeRule.onNodeWithText("Close").performClick()
        idle()

        composeRule.onNodeWithTag("cropPreview").assertDoesNotExist()
        val screen = composeRule.onRoot().fetchSemanticsNode().boundsInWindow
        assertEquals(screen, composeRule.onNodeWithTag("page").fetchSemanticsNode().boundsInWindow)
        assertEquals(reading, readerRenders())
    }

    @Test
    fun frameFollowsTheSliderAndCutsNothingAtTheWholePage() {
        showZoomed()
        openPanel()
        awaitPreview()

        composeRule.onNodeWithTag("zoomSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        awaitRendered { (_, region) -> !region.isZoomed() }

        val preview = composeRule.onNodeWithTag("cropPreview").fetchSemanticsNode().boundsInWindow
        assertRect(preview, "cropFrame")
        composeRule.onNodeWithTag("cutInk").assertDoesNotExist()
    }

    @Test
    fun previewFollowsPageTurns() {
        showZoomed()
        openPanel()
        awaitPreview()

        // On the page, above the panel.
        composeRule.onNodeWithTag("cropPreview").performTouchInput { click(topRight + Offset(-10f, 10f)) }
        awaitRendered { it.first == 1 }
        awaitPreview()

        assertEquals(1, pages.renders.last().index)
        composeRule.onNodeWithContentDescription("Page 2, whole").assertExists()
        composeRule.onNodeWithText("2 / 3").assertExists()
    }

    /** Waits for the open zoom panel's whole-page preview of the page being read. */
    private fun awaitPreview() {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            idle()
            val index = readerRenders().last().first
            val shown = composeRule.onAllNodesWithContentDescription("Page ${index + 1}, whole")
                .fetchSemanticsNodes().isNotEmpty()
            if (shown) break
            check(System.currentTimeMillis() < deadline) { "No preview: ${pages.renders}" }
            Thread.sleep(10)
        }
    }

    /** This region of a page, where [preview] shows [shown] of it. */
    private fun Region.within(shown: Region, preview: Rect) = Rect(
        left = preview.left + (left - shown.left) / shown.width * preview.width,
        top = preview.top + (top - shown.top) / shown.height * preview.height,
        right = preview.left + (left + width - shown.left) / shown.width * preview.width,
        bottom = preview.top + (top + height - shown.top) / shown.height * preview.height,
    )

    /** The node tagged [tag] is laid out over [expected], to the pixel. */
    private fun assertRect(expected: Rect, tag: String) {
        val actual = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        for ((a, e) in listOf(actual.left to expected.left, actual.top to expected.top, actual.right to expected.right, actual.bottom to expected.bottom)) {
            assertTrue("$tag: $actual, expected $expected", abs(a - e) <= 1f)
        }
    }

    @Test
    fun tapsOnThePanelDoNotTurnThePage() {
        showZoomed()
        openPanel()
        val renders = pages.renders.size

        composeRule.onNodeWithTag("lostInkCurve").performTouchInput { click(centerRight) }
        composeRule.onNodeWithTag("lostInkCurve").performTouchInput { click(centerLeft) }
        idle()

        composeRule.onNodeWithText("1 / 3").assertExists()
        assertEquals(renders, pages.renders.size)
    }

    @Test
    fun sliderSavesTheChosenZoomAndRerenders() {
        showZoomed()
        val auto = lastRendered().second
        openPanel()

        // Slide all the way left: the whole page.
        composeRule.onNodeWithTag("zoomSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        awaitRendered { (_, region) -> !region.isZoomed() }

        assertEquals(listOf<Float?>(1f), saves)
        val whole = lastRendered().second
        assertTrue("$whole vs $auto", whole.width > auto.width)
        assertTrue("$whole", whole.contains(0f, 0f, 600f, 800f))
        composeRule.onNodeWithText("Zoom 1.00× · nothing cut off").assertExists()
    }

    @Test
    fun draggingTheSliderSavesWhereItIsReleased() {
        showZoomed()
        openPanel()

        // Press mid-track and drag to the far left: a tap would only reach the middle.
        composeRule.onNodeWithTag("zoomSlider").performTouchInput {
            swipe(center, centerLeft - Offset(50f, 0f), durationMillis = 300)
        }
        awaitRendered { (_, region) -> !region.isZoomed() }

        assertEquals(listOf<Float?>(1f), saves)
        composeRule.onNodeWithText("Zoom 1.00× · nothing cut off").assertExists()
    }

    // Regression: the panel sat on the bottom system-gesture strip, where the Boox's SystemUI
    // takes over finger drags, so the slider took taps but not drags.
    @Test
    fun panelControlsStayAboveTheBottomGestureStrip() {
        showZoomed()
        openPanel()
        val strip = 60
        val view = composeRule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        composeRule.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, strip))
                    .build(),
            )
        }
        idle()

        val screenBottom = composeRule.onRoot().fetchSemanticsNode().boundsInWindow.bottom
        val slider = composeRule.onNodeWithTag("zoomSlider").fetchSemanticsNode().boundsInWindow
        assertTrue(
            "slider $slider overlaps the strip below ${screenBottom - strip}",
            slider.bottom <= screenBottom - strip,
        )
    }

    @Test
    fun autoButtonForgetsTheSavedZoom() {
        show(savedScale = 1f)
        awaitRendered { (_, region) -> !region.isZoomed() }
        openPanel()

        composeRule.onNodeWithText("Auto").performClick()
        awaitRendered { (_, region) -> region.isZoomed() }

        assertEquals(listOf<Float?>(null), saves)
        composeRule.onNodeWithText("Auto ✓").assertExists()
        val region = lastRendered().second
        assertTrue("$region", region.top + region.height < 760f)
    }

    @Test
    fun reanalyzesWhenTheScreenChangesShape() {
        readerSize = 300.dp to 400.dp
        showZoomed()
        val masksBefore = pages.masked.size

        // Landscape, within the 360dp-wide test screen. The padded page is now 800 points tall.
        readerSize = 340.dp to 255.dp
        awaitRendered { (_, r) -> abs(r.width / r.height - 4f / 3) < 0.01f && r.height < 790f }

        // A fresh analysis for the new shape, which fits the text block's height.
        assertTrue(pages.masked.size > masksBefore)
        val region = lastRendered().second
        assertTrue("$region", region.top <= 100f && region.top + region.height >= 700f)
    }

    @Test
    fun zeroSizeLayoutRendersNothing() {
        readerSize = 0.dp to 0.dp
        show()
        idle()
        Thread.sleep(100)
        idle()

        assertEquals(emptyList<Int>(), pages.masked.toList())
        assertEquals(emptyList<Pair<Int, Region>>(), pages.rendered.toList())
    }

    @Test
    fun backArrowClosesWithoutTurningThePage() {
        showZoomed()
        val renders = pages.rendered.size

        composeRule.onNodeWithContentDescription("Back to papers").performClick()
        idle()

        assertEquals(1, closes)
        assertEquals(renders, pages.rendered.size)
    }

    @Test
    fun backArrowStillWorksWithThePanelOpen() {
        showZoomed()
        openPanel()

        composeRule.onNodeWithContentDescription("Back to papers").performClick()

        assertEquals(1, closes)
    }

    @Test
    @Config(qualifiers = "+night")
    fun panelIsBlackOnWhiteInDarkTheme() {
        showZoomed()
        openPanel()

        val window = drawWindow()
        for (label in listOf("Close", "Auto ✓", "1 / 3")) {
            val bounds = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInWindow
            val luminances = (bounds.left.toInt() until bounds.right.toInt()).flatMap { x ->
                (bounds.top.toInt() until bounds.bottom.toInt()).map { y ->
                    Color(window.getPixel(x, y)).luminance()
                }
            }
            assertTrue("$label: no dark text", luminances.min() < 0.3f)
            assertTrue("$label: no light background", luminances.max() > 0.9f)
        }
        val panel = composeRule.onNodeWithTag("zoomPanel").fetchSemanticsNode().boundsInWindow
        assertEquals(
            Color.White,
            Color(window.getPixel(panel.center.x.toInt(), (panel.top + 3).toInt())),
        )
    }

    // captureToImage() times out under Robolectric, so draw the window ourselves.
    private fun drawWindow(): Bitmap {
        val view = composeRule.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            .also { view.draw(Canvas(it)) }
    }

    @Test
    @Config(qualifiers = "w620dp-h826dp")
    fun cropPreviewScreenshot() {
        // Page 2 of "Attention Is All You Need", zoomed in far enough to cut ink off its edges.
        pages = FakePages(List(3) { maskFixture("attention-page2.txt") })
        show(savedScale = 0.8f)
        // The page is 1920 points wide.
        awaitRendered { (_, region) -> region.width < 1900f }
        openPanel()
        awaitPreview()

        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/reader_crop_preview.png")
    }

    @Test
    fun zoomPanelScreenshot() {
        showZoomed()
        openPanel()
        awaitPreview()

        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/reader_zoom_panel.png")
    }
}
