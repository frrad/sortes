package dev.sortes.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reader's content takes exactly the part of the window the system leaves visible: the
 * whole window with the system bars hidden, and none of a bar that shows or a display cutout.
 * The crop is analysed and drawn for that area, so if part of it were under a bar, the crop's
 * top rows would be hidden there.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Like the Boox Note Air5 C's: a 62 px status bar and a 126 px navigation bar.
    private val statusBar = Insets.of(0, 62, 0, 0)
    private val navigationBar = Insets.of(0, 0, 0, 126)

    @Before
    fun edgeToEdge() {
        // As MainActivity's enableEdgeToEdge does: the app draws under the bars and gets their insets.
        WindowCompat.setDecorFitsSystemWindows(composeRule.activity.window, false)
        composeRule.setContent {
            ReaderFrame(onClose = {}) { Box(Modifier.fillMaxSize().testTag("content")) }
        }
        composeRule.waitForIdle()
    }

    /** Sends the window [insets] as the system would. */
    private fun dispatch(insets: WindowInsetsCompat) {
        composeRule.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView, insets)
        }
        composeRule.waitForIdle()
    }

    private fun bars(visible: Boolean, cutout: DisplayCutoutCompat? = null): WindowInsetsCompat {
        val builder = WindowInsetsCompat.Builder()
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), statusBar)
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigationBar)
            .setVisible(WindowInsetsCompat.Type.statusBars(), visible)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), visible)
            .setDisplayCutout(cutout)
        if (visible) {
            builder.setInsets(WindowInsetsCompat.Type.statusBars(), statusBar)
            builder.setInsets(WindowInsetsCompat.Type.navigationBars(), navigationBar)
        }
        if (cutout != null) {
            builder.setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom),
            )
        }
        return builder.build()
    }

    private fun contentPx(): IntArray {
        val bounds = composeRule.onNodeWithTag("content").getBoundsInRoot()
        return with(composeRule.density) {
            intArrayOf(bounds.left.roundToPx(), bounds.top.roundToPx(), bounds.right.roundToPx(), bounds.bottom.roundToPx())
        }
    }

    private fun windowPx(): IntArray {
        val view = composeRule.activity.window.decorView
        return intArrayOf(0, 0, view.width, view.height)
    }

    @Test
    fun withTheBarsHiddenTheContentIsTheWholeWindow() {
        dispatch(bars(visible = false))
        assertEquals(windowPx().toList(), contentPx().toList())
    }

    @Test
    fun noneOfTheContentIsUnderBarsThatShow() {
        dispatch(bars(visible = true))
        val (_, _, width, height) = windowPx()
        assertEquals(listOf(0, 62, width, height - 126), contentPx().toList())
    }

    @Test
    fun theContentAndBackButtonKeepClearOfACutout() {
        val cutout = DisplayCutoutCompat(android.graphics.Rect(0, 80, 0, 0), null)
        dispatch(bars(visible = false, cutout = cutout))
        val (_, _, width, height) = windowPx()
        assertEquals(listOf(0, 80, width, height), contentPx().toList())
        val button = composeRule.onNodeWithContentDescription("Back to papers").getBoundsInRoot()
        assertTrue("Back button at ${button.top}", with(composeRule.density) { button.top.roundToPx() } >= 80)
    }
}
