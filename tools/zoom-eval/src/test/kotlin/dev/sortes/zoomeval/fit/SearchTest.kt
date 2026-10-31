package dev.sortes.zoomeval.fit

import dev.sortes.app.zoom.CropConfig
import dev.sortes.zoomeval.weightValues
import dev.sortes.zoomeval.with
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val base = CropConfig.DEFAULT

    @Test
    fun overridesReachTheTextModel() {
        val c = base.with(mapOf("comfortable" to "27", "lines" to "0.2", "gamma" to "0.08"))
        assertEquals(27.0, c.text.comfortable, 0.0)
        assertEquals(0.2, c.text.lines, 0.0)
        assertEquals(0.08, c.weights.gamma, 0.0)
        // Everything else is the default's, and so is the name.
        assertEquals(base.text.copy(comfortable = 27.0, lines = 0.2), c.text)
        assertEquals(base.name, c.name)
        assertEquals("comfortable=27.00, gamma=0.08000, lines=0.2000", c.diff())
        assertThrows(IllegalArgumentException::class.java) { base.with(mapOf("omega" to "1")) }
        // Invalid values are refused by the weights themselves.
        assertThrows(IllegalArgumentException::class.java) { base.with(mapOf("floor" to "2")) }
    }

    @Test
    fun weightValuesCoverBothModels() {
        val v = base.weightValues()
        assertEquals(28.0, v.getValue("comfortable"), 0.0)
        assertEquals(0.5, v.getValue("beta"), 0.0)
        for (name in SEARCH_WEIGHTS) assertTrue(name, name in v)
    }

    @Test
    fun neighboursStepEachWayOnTheWeightsOwnScale() {
        assertEquals(listOf(0.05, 0.075, 0.133, 0.2), neighbours("gamma", 0.1))
        assertEquals(listOf(0.0, 0.0025, 0.00375, 0.00665, 0.01), neighbours("delta", 0.005))
        assertEquals(listOf(0.25, 0.375, 0.665, 1.0), neighbours("beta", 0.5))
        assertEquals(listOf(26.0, 27.0, 29.0, 30.0), neighbours("comfortable", 28.0))
        assertEquals(listOf(0.0, 0.005, 0.015, 0.02), neighbours("autoMargin", 0.01))
        assertEquals(listOf(1.0, 3.0), neighbours("blockGap", 2.0))
        assertEquals(listOf(0.0, 0.05, 0.2, 0.3), neighbours("floor", 0.1))
        // Off weights try small values; weights that can't be off don't try 0.
        assertEquals(listOf(30.0, 100.0, 300.0), neighbours("epsilon", 0.0))
        assertFalse(0.0 in neighbours("zeta", 0.5))
        assertFalse(0.0 in neighbours("power", 16.0))
        // Never outside a weight's range.
        assertEquals(listOf(0.0, 0.45), neighbours("floor", 0.9))
        assertEquals(listOf(1.0), neighbours("blockGap", 0.0))
        assertThrows(IllegalArgumentException::class.java) { neighbours("omega", 1.0) }
    }

    @Test
    fun aCoordinateTriesItsGridAroundTheCurrentConfig() {
        val one = Coordinate.parse("comfortable").candidates(base)
        assertEquals(listOf(26.0, 27.0, 29.0, 30.0), one.map { it.text.comfortable })
        assertTrue(one.all { it.weights == base.weights && it.text.power == base.text.power })
        // Two weights: every combination of their values but the current one.
        val two = Coordinate.parse("blockGap+autoMargin").candidates(base)
        assertEquals(3 * 5 - 1, two.size)
        assertEquals(two.size, two.distinct().size)
        assertFalse(two.any { it.weights == base.weights })
        assertTrue(two.all { it.weights.blockGap in 1..3 })
        assertEquals("blockGap+autoMargin", Coordinate.parse("blockGap+autoMargin").toString())
        assertThrows(IllegalArgumentException::class.java) { Coordinate.parse("comfortable+omega") }
    }

    /** A trial whose units on each paper are given. */
    private fun trial(config: CropConfig, units: Map<String, Double>) =
        Trial(config, units.map { (paper, u) -> PaperScore(paper, 0.8, 0.8, u, emptyList()) })

    @Test
    fun descentWalksDownhillOnTheTrainingPapersOnly() = runBlocking {
        // Paper a likes comfortable 25 and lines 0.1; paper b (held out) likes comfortable 31.
        fun units(c: CropConfig) =
            mapOf(
                "a" to abs(c.text.comfortable - 25) + abs(c.text.lines - 0.1) * 10,
                "b" to abs(c.text.comfortable - 31) * 3,
            )
        var evaluated = 0
        val d =
            coordinateDescent(
                trial(base, units(base)),
                listOf(Coordinate.parse("comfortable"), Coordinate.parse("lines")),
                train = listOf("a"),
                sweeps = 5,
                minGain = 0.5,
                evaluate = {
                    evaluated++
                    trial(it, units(it))
                },
            )
        // 28 → 26 → 25 (the second sweep), lines stays: it's already best. A third sweep finds
        // nothing and stops.
        assertEquals(25.0, d.best.config.text.comfortable, 0.0)
        assertEquals(0.1, d.best.config.text.lines, 0.0)
        assertEquals(listOf(1, 1, 2, 2, 3, 3), d.steps.map { it.sweep })
        assertEquals(listOf(true, false, true, false, false, false), d.steps.map { it.moved })
        assertEquals(evaluated, d.tried.size - 1)
        // The held-out paper got worse; the search didn't see it.
        assertTrue(d.best.unitsOn(listOf("b")) > d.start.unitsOn(listOf("b")))
    }

    @Test
    fun smallGainsDontMove() = runBlocking {
        fun units(c: CropConfig) = mapOf("a" to abs(c.text.comfortable - 27) * 0.1)
        val d =
            coordinateDescent(
                trial(base, units(base)),
                listOf(Coordinate.parse("comfortable")),
                listOf("a"),
                sweeps = 3,
                minGain = 0.5,
                evaluate = { trial(it, units(it)) },
            )
        assertSame(d.start, d.best)
        assertEquals(1, d.steps.size)
    }

    @Test
    fun aStepIsFragileWhenOnePaperDecidesIt() {
        val incumbent = trial(base, mapOf("a" to 5.0, "b" to 5.0, "c" to 5.0))
        // Better on the whole only because of a; c is slightly worse.
        val candidate =
            trial(base.with(mapOf("gamma" to "0.2")), mapOf("a" to 1.0, "b" to 5.0, "c" to 5.2))
        assertSame(candidate, decide(incumbent, listOf(candidate), listOf("a", "b", "c"), 0.5))
        assertSame(incumbent, decide(incumbent, listOf(candidate), listOf("b", "c"), 0.5))
        val step = Step(1, Coordinate.parse("gamma"), incumbent, listOf(candidate), candidate)
        val d = Descent(incumbent, listOf(step), listOf("a", "b", "c"), 0.5)
        assertEquals(listOf("a"), d.fragile(step))
        assertSame(candidate, d.best)
        // A step that kept the incumbent is fragile if leaving out c would have moved it... it
        // wouldn't here.
        val stay =
            Step(
                1,
                Coordinate.parse("gamma"),
                incumbent,
                listOf(trial(base, mapOf("a" to 5.0, "b" to 4.8, "c" to 5.0))),
                incumbent,
            )
        assertEquals(
            emptyList<String>(),
            Descent(incumbent, listOf(stay), listOf("a", "b", "c"), 0.5).fragile(stay),
        )
    }
}
