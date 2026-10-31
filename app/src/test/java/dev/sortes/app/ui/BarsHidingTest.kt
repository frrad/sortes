package dev.sortes.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HideSystemBars] says the bars are on their way out ([barsHiding]) from when it asks them to hide
 * until the window's insets first report them gone, so the reader isn't laid out (and its page
 * rendered) around bars that are about to go, or until it's clear they're staying.
 */
@RunWith(RobolectricTestRunner::class)
class BarsHidingTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var showing by mutableStateOf(true)
    private var hiding: Boolean? = null

    @Before
    fun setUp() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { hiding = barsHiding(showing) }
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** Runs a few frames: the effect, then the recomposition it causes. */
    private fun frames() =
        repeat(3) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }

    @Test
    fun hidingUntilTheBarsAreFirstGone() {
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(true, hiding)

        composeRule.runOnIdle { showing = false }
        frames()

        assertEquals(false, hiding)
        // Bars brought back later (a swipe) are bars that show: not hiding any more.
        composeRule.runOnIdle { showing = true }
        frames()
        assertEquals(false, hiding)
    }

    @Test
    fun barsStillShowingAfterTheTimeoutAreTakenToStay() {
        composeRule.mainClock.advanceTimeBy(HIDE_TIMEOUT_MS - 100)
        assertEquals(true, hiding)

        composeRule.mainClock.advanceTimeBy(200)

        assertEquals(false, hiding)
    }
}
