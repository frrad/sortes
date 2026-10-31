package dev.sortes.app.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderFrameTest {

    @get:Rule val composeRule = createComposeRule()

    private var closes = 0
    private var pageTaps = 0

    private fun setFrame() {
        composeRule.setContent {
            ReaderFrame(onClose = { closes++ }) {
                // Stands in for the page, which turns on any tap.
                Box(
                    Modifier.fillMaxSize().testTag("page").pointerInput(Unit) {
                        detectTapGestures { pageTaps++ }
                    }
                )
            }
        }
    }

    @Test
    fun backButtonClosesWithoutTurningThePage() {
        setFrame()
        composeRule.onNodeWithContentDescription("Back to papers").performClick()
        composeRule.waitForIdle()
        assertEquals(1, closes)
        assertEquals(0, pageTaps)
    }

    @Test
    fun tapsElsewhereStillReachThePage() {
        setFrame()
        composeRule.onNodeWithTag("page").performTouchInput { click(center) }
        composeRule.waitForIdle()
        assertEquals(0, closes)
        assertEquals(1, pageTaps)
    }
}
