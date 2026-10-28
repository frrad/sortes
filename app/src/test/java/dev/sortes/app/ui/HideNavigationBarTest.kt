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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HideNavigationBarTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val decorView get() = composeRule.activity.window.decorView
    private val controller
        get() = WindowCompat.getInsetsController(composeRule.activity.window, decorView)

    private fun navigationBarVisible(): Boolean =
        ViewCompat.getRootWindowInsets(decorView)!!
            .isVisible(WindowInsetsCompat.Type.navigationBars())

    @Test
    fun hidesNavigationBarOnlyWhileComposed() {
        var hidden by mutableStateOf(false)
        composeRule.setContent { if (hidden) HideNavigationBar() }
        composeRule.waitForIdle()
        // Robolectric reports no navigation bar state until something hides or shows it.
        assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, controller.systemBarsBehavior)

        hidden = true
        composeRule.waitForIdle()
        assertFalse(navigationBarVisible())
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            controller.systemBarsBehavior,
        )

        hidden = false
        composeRule.waitForIdle()
        assertTrue(navigationBarVisible())
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            controller.systemBarsBehavior,
        )
    }
}
