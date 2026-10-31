package dev.sortes.app.ui

import android.os.Build
import android.os.SystemClock
import android.os.Trace
import android.util.Log

/**
 * Times opening a paper, from the tap on it to its first page settled on screen at its crop, and
 * counts what the reader drew on the way: on e-ink every distinct frame is a refresh, so a whole
 * page drawn before the zoomed one looks like an animation.
 *
 * The reader [mark]s each step (the PDF opened, its hash, the analysis) and reports every page it
 * draws ([pageDrawn]) and message it shows ([messageShown]). The first zoomed page drawn settles
 * the timeline: it logs one summary line (tag `SortesOpen`) and ignores anything after, such as
 * page turns. Steps are also written as trace sections, for Perfetto.
 *
 * Thread-safe: steps can be marked from worker threads.
 */
class OpenTimeline(
    val paperId: Long,
    /** When the paper was tapped, on [clock]'s time base. */
    private val startedAt: Long,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val marks = mutableListOf<Pair<String, Long>>()
    private val shown = mutableListOf<String>()
    private var lastPage: Any? = null
    private var lastMessage: String? = null

    // Tells this opening's trace section from others', even of the same paper.
    private val cookie = cookies.incrementAndGet()

    /** Milliseconds from the tap to the settled page, once it's drawn. */
    @get:Synchronized
    var settledMillis: Long? = null
        private set

    /** Everything drawn so far, in order: messages and pages ("whole page 1", "zoomed page 1"). */
    @get:Synchronized
    val frames: List<String> get() = shown.toList()

    /** The steps marked so far, with their milliseconds since the tap. */
    @get:Synchronized
    val steps: List<Pair<String, Long>> get() = marks.toList()

    init {
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection(SECTION, cookie)
    }

    private fun elapsed() = clock() - startedAt

    /** Records that [step] happened now. */
    @Synchronized
    fun mark(step: String) {
        if (settledMillis != null) return
        marks += step to elapsed()
        Trace.beginSection(step.take(MAX_SECTION_NAME))
        Trace.endSection()
    }

    /** Records that the reader shows the message [text] (e.g. "Loading…") instead of a page. */
    @Synchronized
    fun messageShown(text: String) {
        if (settledMillis != null || text == lastMessage) return
        lastMessage = text
        lastPage = null
        shown += "message \"$text\""
        mark("message \"$text\"")
    }

    /**
     * Records that the reader drew [page] (any object identifying one rendered bitmap; drawing the
     * same one again is not a new frame): page [index], at its crop if [zoomed] or else whole.
     */
    @Synchronized
    fun pageDrawn(page: Any, index: Int, zoomed: Boolean) {
        if (settledMillis != null || page === lastPage) return
        lastPage = page
        lastMessage = null
        val frame = "${if (zoomed) "zoomed" else "whole"} page ${index + 1}"
        shown += frame
        mark("drew $frame")
        if (zoomed) settle()
    }

    private fun settle() {
        val millis = elapsed()
        settledMillis = millis
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(SECTION, cookie)
        log(summary())
    }

    /** One line: the settle time, the frames drawn, and each step's time since the tap. */
    @Synchronized
    fun summary(): String {
        val head = settledMillis?.let { "settled in $it ms" } ?: "not settled after ${elapsed()} ms"
        return "Opened paper $paperId: $head; ${shown.size} frame(s) [${shown.joinToString(", ")}]; " +
            marks.joinToString(", ") { (step, ms) -> "$step +$ms" }
    }

    /** Logs the [summary] if the paper never settled (closed first, or no zoom), once. */
    @Synchronized
    fun abandon() {
        if (settledMillis != null) return
        log(summary())
        settledMillis = elapsed()
        if (Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection(SECTION, cookie)
    }

    companion object {
        const val TAG = "SortesOpen"
        private const val SECTION = "Sortes open paper"
        private const val MAX_SECTION_NAME = 127

        /** A tap older than this isn't what opened the reader (e.g. restored after process death). */
        private const val TAP_MAX_AGE_MS = 5_000L

        private val cookies = java.util.concurrent.atomic.AtomicInteger()

        @Volatile
        private var lastTap: Pair<Long, Long>? = null

        /** Records that paper [paperId] was tapped now, to time its opening from. */
        fun tapped(paperId: Long) {
            lastTap = paperId to SystemClock.elapsedRealtime()
        }

        /** A timeline for opening [paperId], from its tap if it was just tapped, else from now. */
        fun begin(paperId: Long): OpenTimeline {
            val now = SystemClock.elapsedRealtime()
            val tap = lastTap?.takeIf { it.first == paperId && now - it.second in 0..TAP_MAX_AGE_MS }
            lastTap = null
            return OpenTimeline(paperId, tap?.second ?: now)
        }
    }
}
