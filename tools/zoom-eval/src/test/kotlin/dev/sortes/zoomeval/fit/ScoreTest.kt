package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CellRect
import dev.sortes.app.zoom.CropModelWeights
import dev.sortes.app.zoom.CropWindow
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.ModelCrops
import dev.sortes.app.zoom.PageCrop
import dev.sortes.app.zoom.PageSize
import dev.sortes.zoomeval.labeller.PageLabel
import dev.sortes.zoomeval.labeller.PaperLabels
import dev.sortes.zoomeval.labeller.Point
import dev.sortes.zoomeval.labeller.Verdict
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ScoreTest {
    private val aspect = 0.75f

    // A 60 x 80 point page (exactly 3:4, so unpadded), one cell per point, text block at 10..50 x
    // 10..70.
    private val size = PageSize(60f, 80f)
    private val mask =
        InkMask(
            60,
            80,
            BooleanArray(60 * 80) { i -> i % 60 in 10 until 50 && i / 60 in 10 until 70 },
        )

    private fun label(verdict: Verdict? = null, centre: Point? = null, comment: String = "") =
        PageLabel(verdict, emptySet(), comment, centre)

    private fun labels(vararg pages: Pair<Int, PageLabel>, scale: Double? = null) =
        PaperLabels("p", null, scale, "", mapOf(*pages))

    private val judged = listOf(Point(30.0, 40.0), Point(30.0, 40.0), Point(30.0, 40.0))

    @Test
    fun movesBecomeTargetsWeightedByConfidence() {
        val t =
            targets(
                labels(
                    1 to label(centre = Point(30.0, 50.0)),
                    2 to label(centre = Point(33.0, 40.0)),
                    3 to label(centre = Point(30.0, 40.0)),
                ),
                emptyMap(),
                0.8,
                judged,
            )
        // 10 pt: deliberate. 3 pt: low confidence. 0: no move, nothing to score.
        assertEquals(listOf(1, 2), t.pages.map { it.page })
        assertEquals(ScoreWeights.HIGH, t.pages[0].centreWeight, 0.0)
        assertEquals(ScoreWeights.LOW, t.pages[1].centreWeight, 0.0)
        // No ideal zoom given: the judged zoom, at half weight.
        assertEquals(0.8, t.scale, 1e-9)
        assertEquals(ScoreWeights.IMPLICIT_ZOOM, t.scaleWeight, 0.0)
    }

    @Test
    fun theInterpretationOverridesAndAddsToLabels() {
        val reading =
            mapOf(
                0 to Interpretation(zoom = "tighter"),
                1 to Interpretation(move = "low"),
                2 to Interpretation(verdict = Verdict.GOOD),
                3 to Interpretation(keep = true, move = "none"),
            )
        val t =
            targets(
                labels(
                    1 to label(centre = Point(30.0, 60.0)),
                    2 to label(comment = "good"),
                    3 to label(comment = "keep it all"),
                ),
                reading,
                0.8,
                judged,
            )
        assertEquals(ScoreWeights.LOW, t.pages.first { it.page == 1 }.centreWeight, 0.0)
        // A good page should stay where it was.
        val good = t.pages.first { it.page == 2 }
        assertEquals(judged[1], good.centre)
        assertEquals(ScoreWeights.GOOD, good.centreWeight, 0.0)
        // Keep without a move: only the ink counts.
        val keep = t.pages.first { it.page == 3 }
        assertNull(keep.centre)
        assertEquals(1.0, keep.keepWeight, 0.0)
        // A "tighter" vote moves the implicit zoom target in by one step.
        assertEquals(0.8 - ScoreWeights.ZOOM_VOTE, t.scale, 1e-9)
    }

    @Test
    fun anIdealZoomIsTheTargetAtFullWeight() {
        val t = targets(labels(1 to label(Verdict.GOOD), scale = 0.75), emptyMap(), 0.8, judged)
        assertEquals(0.75, t.scale, 1e-9)
        assertEquals(1.0, t.scaleWeight, 0.0)
    }

    @Test
    fun unlabelledPagesAreNoOpinion() {
        val t = targets(labels(2 to label(Verdict.BAD)), emptyMap(), 0.8, judged)
        // A bad verdict without a move says change, but not to what: nothing to score yet.
        assertEquals(emptyList<PageTarget>(), t.pages)
    }

    @Test
    fun aBadPageCanAskForAnotherPagesCrop() {
        // "Should be cropped like the rest": page 2 was judged at another window; page 1's is the
        // target.
        val judged = listOf(Point(30.0, 40.0), Point(30.0, 25.0), Point(30.0, 40.0))
        val t =
            targets(
                labels(2 to label(Verdict.BAD, comment = "same as the rest")),
                mapOf(2 to Interpretation(move = "high", like = 1)),
                0.8,
                judged,
            )
        val page = t.pages.single()
        assertEquals(Point(30.0, 40.0), page.centre)
        assertEquals(ScoreWeights.HIGH, page.centreWeight, 0.0)
        assertEquals("like p1 (100%)", page.why)
        // Like a page with the same crop: no move, nothing to score.
        assertEquals(
            emptyList<PageTarget>(),
            targets(
                    labels(3 to label(Verdict.BAD)),
                    mapOf(3 to Interpretation(move = "high", like = 1)),
                    0.8,
                    judged,
                )
                .pages,
        )
    }

    @Test
    fun aHorizontalReadingScoresOnlyTheMoveAcross() {
        // Moved 5 across and 20 up, but the vertical part kept a running head the owner drops.
        val t =
            targets(
                labels(1 to label(centre = Point(35.0, 20.0)), scale = 0.9),
                mapOf(
                    0 to Interpretation(zoom = "unset"),
                    1 to Interpretation(move = "medium", horizontal = true),
                ),
                0.8,
                judged,
            )
        // The zoom the owner set answered the head too: back to the zoom they saw, at half weight.
        assertEquals(0.8, t.scale, 1e-9)
        assertEquals(ScoreWeights.IMPLICIT_ZOOM, t.scaleWeight, 0.0)
        val window = windowAt(Point(30.0, 40.0), 0.5, size, aspect)
        assertEquals(
            5.0,
            score(
                    t,
                    crops(window, cellsOf(window, mask, aspect)),
                    listOf(mask),
                    listOf(size),
                    aspect,
                )
                .pages
                .single()
                .centreError!!,
            1e-3,
        )
    }

    @Test
    fun windowsAndCentresRoundTrip() {
        val w = windowAt(Point(25.0, 45.0), 0.5, size, aspect)
        // An unpadded 3:4 page: the frame is the page, so fractions are plain.
        assertEquals(25f / 60 - 0.25f, w.left, 1e-6f)
        assertEquals(45f / 80 - 0.25f, w.top, 1e-6f)
        val c = centreOf(w, size, aspect)
        assertEquals(25.0, c.x, 1e-4)
        assertEquals(45.0, c.y, 1e-4)
        // Near an edge the window stays in the frame.
        val edge = windowAt(Point(1.0, 1.0), 0.5, size, aspect)
        assertEquals(0f, edge.left)
        assertEquals(0f, edge.top)
    }

    @Test
    fun keptButCutCountsInkTheIdealKeepsAndTheModelDoesnt() {
        val all = CellRect(0, 0, 60, 80)
        assertEquals(0, keptButCut(mask, all, all))
        // The model shows only the left half: the right half of the block (20 x 60) is lost.
        assertEquals(20 * 60, keptButCut(mask, all, CellRect(0, 0, 30, 80)))
        // Ink the ideal doesn't keep doesn't count.
        assertEquals(0, keptButCut(mask, CellRect(0, 0, 30, 80), CellRect(0, 0, 30, 80)))
    }

    private fun crops(window: CropWindow, cells: CellRect) =
        ModelCrops(
            scale = window.scale,
            windows = listOf(window),
            pages = listOf(PageCrop(0, window, cells, 0, 0.0, 0.0, 0.0)),
            weights = CropModelWeights(),
            totalInk = mask.inkCount.toLong(),
        )

    @Test
    fun scoresPositionInkAndZoom() {
        val target =
            PaperTargets(
                "p",
                0.8,
                1.0,
                listOf(PageTarget(1, Point(30.0, 40.0), 1.0, Point(30.0, 40.0), 1.0, "moved")),
            )
        // The model's window: scale 0.75 (45 x 60), centred 5 pt right of the target, so it cuts
        // the left edge of the block that the ideal (scale 0.8, centred) keeps.
        val window = windowAt(Point(35.0, 40.0), 0.75, size, aspect)
        val score =
            score(
                target,
                crops(window, cellsOf(window, mask, aspect)),
                listOf(mask),
                listOf(size),
                aspect,
            )
        val page = score.pages.single()
        assertEquals(5.0, page.centreError!!, 0.01)
        assertEquals(5.0 / ScoreWeights.POINTS_PER_UNIT, page.positionUnits, 0.01)
        // Window cells 13..58 across: block columns 10..12 are cut, 3 of 40 columns.
        val cells = cellsOf(window, mask, aspect)
        assertEquals(13, cells.left)
        assertEquals(100.0 * 3 * 60 / (40 * 60), page.keepCutPercent, 0.01)
        assertEquals(
            page.keepCutPercent * ScoreWeights.KEEP_UNITS_PER_PERCENT,
            page.keepUnits,
            1e-9,
        )
        // 0.05 of scale from the target.
        assertEquals(5 * ScoreWeights.ZOOM_UNITS_PER_HUNDREDTH, score.zoomUnits, 1e-6)
        assertEquals(score.zoomUnits + page.units, score.units, 1e-9)
    }

    @Test
    fun aGoodPageCostsNothingWithinItsTolerance() {
        val centre = Point(30.0, 40.0)
        val good =
            PaperTargets(
                "p",
                0.8,
                1.0,
                listOf(
                    PageTarget(
                        1,
                        centre,
                        ScoreWeights.GOOD,
                        centre,
                        ScoreWeights.GOOD,
                        "good",
                        tolerance = ScoreWeights.GOOD_TOLERANCE,
                    )
                ),
            )
        fun units(dx: Double): Double {
            val window = windowAt(Point(30.0 + dx, 40.0), 0.6, size, aspect)
            return score(
                    good,
                    crops(window, cellsOf(window, mask, aspect)),
                    listOf(mask),
                    listOf(size),
                    aspect,
                )
                .pages
                .single()
                .positionUnits
        }
        assertEquals(0.0, units(5.0), 1e-9)
        assertEquals(ScoreWeights.GOOD * 2.0 / ScoreWeights.POINTS_PER_UNIT, units(10.0), 1e-6)
        // The tolerance belongs to good verdicts: targets() sets it there and nowhere else.
        val t =
            targets(
                labels(2 to label(comment = "good"), 1 to label(centre = Point(30.0, 60.0))),
                mapOf(2 to Interpretation(verdict = Verdict.GOOD)),
                0.8,
                listOf(Point(30.0, 40.0), Point(30.0, 40.0)),
            )
        assertEquals(ScoreWeights.GOOD_TOLERANCE, t.pages.first { it.page == 2 }.tolerance, 0.0)
        assertEquals(0.0, t.pages.first { it.page == 1 }.tolerance, 0.0)
    }

    @Test
    fun interpretationsParseAndRejectBadRows() {
        val dir = Files.createTempDirectory("interp").toFile()
        val file = dir.resolve("interpretation.tsv")
        file.writeText("# comment\np\t*\t-\t-\t-\taffirmed\tnote\np\t3\tmedium\tyes\tgood\t-\t\n")
        val read = readInterpretations(file)
        assertEquals(Interpretation(zoom = "affirmed"), read.getValue("p").getValue(0))
        assertEquals(
            Interpretation(move = "medium", keep = true, verdict = Verdict.GOOD),
            read.getValue("p").getValue(3),
        )
        file.writeText("p\t4\thigh@2\tno\t-\t-\t\n")
        assertEquals(
            Interpretation(move = "high", like = 2),
            readInterpretations(file).getValue("p").getValue(4),
        )
        file.writeText("p\t3\tsure\tyes\t-\t-\t\n")
        assertThrows(IllegalArgumentException::class.java) { readInterpretations(file) }
        file.writeText("p\t4\tmedium/x\tno\t-\tunset\t\n")
        assertEquals(
            Interpretation(move = "medium", horizontal = true, zoom = "unset"),
            readInterpretations(file).getValue("p").getValue(4),
        )
        file.writeText("p\t3\thigh@two\tno\t-\t-\t\n")
        assertThrows(IllegalArgumentException::class.java) { readInterpretations(file) }
        assertEquals(emptyMap<String, Any>(), readInterpretations(dir.resolve("missing.tsv")))
        dir.deleteRecursively()
    }
}
