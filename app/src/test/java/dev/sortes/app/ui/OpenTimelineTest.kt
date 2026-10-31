package dev.sortes.app.ui

import android.os.SystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

// Robolectric for android.os.Trace and Build.
@RunWith(RobolectricTestRunner::class)
class OpenTimelineTest {

    private var now = 1_000L
    private val logged = mutableListOf<String>()
    private val timeline = OpenTimeline(paperId = 7, startedAt = 1_000L, clock = { now }, log = { logged += it })

    @Test
    fun settlesOnTheFirstZoomedPageDrawn() {
        val whole = Any()
        val zoomed = Any()
        now += 40
        timeline.mark("pdf opened")
        now += 60
        timeline.pageDrawn(whole, 0, zoomed = false)
        now += 10
        timeline.pageDrawn(whole, 0, zoomed = false) // Drawn again: not a new frame.
        assertNull(timeline.settledMillis)
        now += 200
        timeline.pageDrawn(zoomed, 0, zoomed = true)

        assertEquals(310L, timeline.settledMillis)
        assertEquals(listOf("whole page 1", "zoomed page 1"), timeline.frames)
        assertEquals(listOf("pdf opened" to 40L, "drew whole page 1" to 100L, "drew zoomed page 1" to 310L), timeline.steps)
        assertEquals(
            listOf("Opened paper 7: settled in 310 ms; 2 frame(s) [whole page 1, zoomed page 1]; pdf opened +40, drew whole page 1 +100, drew zoomed page 1 +310"),
            logged,
        )
    }

    @Test
    fun ignoresWhatHappensOnceSettled() {
        timeline.pageDrawn(Any(), 0, zoomed = true)
        now += 500
        timeline.mark("later")
        timeline.pageDrawn(Any(), 1, zoomed = true)
        timeline.messageShown("Couldn't render")
        timeline.abandon()

        assertEquals(0L, timeline.settledMillis)
        assertEquals(listOf("zoomed page 1"), timeline.frames)
        assertEquals(1, logged.size)
    }

    @Test
    fun messagesAreFramesToo() {
        timeline.messageShown("Loading…")
        timeline.messageShown("Loading…")
        timeline.pageDrawn(Any(), 0, zoomed = true)

        assertEquals(listOf("message \"Loading…\"", "zoomed page 1"), timeline.frames)
    }

    @Test
    fun aPaperClosedBeforeItSettlesIsLoggedOnce() {
        timeline.pageDrawn(Any(), 0, zoomed = false)
        now += 90
        timeline.abandon()
        timeline.abandon()

        assertEquals(1, logged.size)
        assertTrue(logged.single(), logged.single().startsWith("Opened paper 7: not settled after 90 ms; 1 frame(s) [whole page 1]"))
    }

    @Test
    fun beginTimesFromTheTapOnThatPaper() {
        OpenTimeline.tapped(3)
        SystemClock.sleep(50) // Robolectric's clock: advances at once.
        val tapped = OpenTimeline.begin(3)
        // The tap is used once: a reader restored later times from when it begins.
        val restored = OpenTimeline.begin(3)
        OpenTimeline.tapped(4)
        val other = OpenTimeline.begin(5)
        SystemClock.sleep(20)

        for (t in listOf(tapped, restored, other)) t.abandon()

        val logs = ShadowLog.getLogsForTag(OpenTimeline.TAG).map { it.msg }
        assertEquals(
            listOf("Opened paper 3: not settled after 70 ms", "Opened paper 3: not settled after 20 ms", "Opened paper 5: not settled after 20 ms"),
            logs.map { it.substringBefore(";") },
        )
    }
}
