package dev.sortes.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The reader's "Loading…" waits: a PDF on disk opens in tens of milliseconds, and a message flashed
 * for that long is two refreshes on e-ink.
 */
@RunWith(RobolectricTestRunner::class)
class DelayedMessageTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun showsOnlyAfterTheDelay() {
        val timeline = OpenTimeline(1, startedAt = 0, clock = { 0 }, log = {})
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            DelayedMessage("Loading…", LOADING_MESSAGE_DELAY_MS, timeline = timeline)
        }
        composeRule.mainClock.advanceTimeBy(LOADING_MESSAGE_DELAY_MS - 100)
        composeRule.onNodeWithText("Loading…").assertDoesNotExist()
        assertEquals(emptyList<String>(), timeline.frames)

        composeRule.mainClock.advanceTimeBy(200)

        composeRule.onNodeWithText("Loading…").assertExists()
        assertEquals(listOf("message \"Loading…\""), timeline.frames)
    }
}
