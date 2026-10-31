package dev.sortes.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.sortes.app.data.Paper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HideSystemBarsTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val decorView
        get() = composeRule.activity.window.decorView

    private val controller
        get() = WindowCompat.getInsetsController(composeRule.activity.window, decorView)

    private fun visible(type: Int): Boolean =
        ViewCompat.getRootWindowInsets(decorView)!!.isVisible(type)

    private fun statusBarVisible() = visible(WindowInsetsCompat.Type.statusBars())

    private fun navigationBarVisible() = visible(WindowInsetsCompat.Type.navigationBars())

    @Test
    fun hidesBothBarsOnlyWhileComposed() {
        var hidden by mutableStateOf(false)
        composeRule.setContent { if (hidden) HideSystemBars() }
        composeRule.waitForIdle()
        // Robolectric reports no bar state until something hides or shows them.
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, controller.systemBarsBehavior)

        hidden = true
        composeRule.waitForIdle()
        assertFalse(statusBarVisible())
        assertFalse(navigationBarVisible())
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            controller.systemBarsBehavior,
        )

        hidden = false
        composeRule.waitForIdle()
        assertTrue(statusBarVisible())
        assertTrue(navigationBarVisible())
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            controller.systemBarsBehavior,
        )
    }

    @Test
    fun readerHidesBothBarsAndRestoresThemOnLeaving() {
        var reading by mutableStateOf(true)
        // An imported paper whose file is missing: the reader opens straight to its error.
        val paper =
            Paper(
                title = "Missing",
                authors = emptyList(),
                abstract = "",
                year = null,
                venue = null,
                localFile = "missing.pdf",
            )
        composeRule.setContent { if (reading) ReaderScreen(paper = paper, onClose = {}) }
        composeRule.waitForIdle()
        assertFalse(statusBarVisible())
        assertFalse(navigationBarVisible())

        reading = false
        composeRule.waitForIdle()
        assertTrue(statusBarVisible())
        assertTrue(navigationBarVisible())
    }
}
