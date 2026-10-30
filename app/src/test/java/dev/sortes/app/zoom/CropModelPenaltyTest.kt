package dev.sortes.app.zoom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CropModelPenaltyTest {
    private fun mask(vararg rows: String) = InkMask(rows[0].length, rows.size,
        rows.flatMap { it.map { c -> c == '#' } }.toBooleanArray())

    private fun cost(f: (CellRect) -> Double) = PageCost { CellCost(f) }

    @Test fun `extra cost participates in solving rescoring and page assignment`() = runBlocking {
        val m = mask("##########", "..........", "#.#.#.#.#.", "..........", "..........")
        val weights = CropModelWeights(beta=0.0, epsilon=0.0, maxWindows=1)
        val extra = cost { c -> if (c.top >= 1) 0.0 else 10.0 }
        val model = CropModel(2f, weights)
        val c = model.prepare(listOf(m), listOf(extra)).solve(0.5f)
        val rescored = model.score(listOf(m), c.pages.map { it.window }, listOf(extra))
        assertEquals(c.total, rescored.total, 1e-9)
        assertEquals((if (c.pages[0].cells.top >= 1) 0.0 else 10.0), c.additionalTerm, 1e-9)
        assertEquals(model.pageCost(c.pages[0]), c.total, 1e-9)
        assertEquals(c.total, model.pageCost(model.assign(m, c, m.inkCount.toDouble(), pageCost = extra)), 1e-9)
        assertEquals(c.total, model.pageCost(model.ownCrop(m, 0.5f, m.inkCount.toDouble(), extra)), 1e-9)
        // Fixed-scale, one-window exhaustive oracle (different positions may tie).
        val p = PaddedPage(m, 2f)
        var best = Double.POSITIVE_INFINITY
        for (y in 0..p.height-p.windowHeight(0.5f)) for (x in 0..p.width-p.windowWidth(0.5f)) {
            best = minOf(best, model.score(listOf(m), listOf(p.windowAt(x,y,0.5f)), listOf(extra)).total)
        }
        assertEquals(best,c.total,1e-9)
        val plain = model.solve(listOf(m),0.5f)
        val zero = model.prepare(listOf(m), listOf(cost { 0.0 })).solve(0.5f)
        assertEquals(plain, zero)
        // A solve can leave the extra costs out.
        assertEquals(plain, model.prepare(listOf(m), listOf(extra)).solve(0.5f, withExtras = false))
    }

    @Test fun `extra penalties also apply on blank pages and across multiple windows`() = runBlocking {
        val a = mask("........", "........", "........", "........", "........", "........", "........", "........")
        val b = mask("........", "........", "........", "........", "........", "........", "........", "........")
        val model = CropModel(1f, CropModelWeights(alpha=0.0,beta=0.0,epsilon=0.0,gamma=0.05))
        val extras = listOf(cost { c -> if (c.left == 0) 0.0 else 100.0 }, cost { c -> if (c.left == 4) 0.0 else 100.0 })
        val c = model.prepare(listOf(a,b), extras).solve(0.5f)
        assertEquals(2,c.k)
        assertEquals(0,c.pages[0].cells.left)
        assertEquals(4,c.pages[1].cells.left)
        assertEquals(0.05,c.total,1e-9)
        assertEquals(c.total,model.score(listOf(a,b),c.pages.map { it.window }, extras).total,1e-9)
    }

    @Test fun `each solve opens each page's cost once per use and drops it`() = runBlocking {
        val m = mask("####....", "####....", "........", "........")
        var opened = 0
        val extra = PageCost { opened++; CellCost { 0.0 } }
        CropModel(2f, CropModelWeights(maxWindows = 1), parallelism = 1).prepare(listOf(m), listOf(extra)).solve(0.5f)
        // Once for the cost table, once for the chosen crop's report.
        assertEquals(2, opened)
    }

    @Test fun `the automatic zoom is chosen without the extra costs, then placed with them`() = runBlocking {
        val m = mask("..........", ".########.", ".########.", ".########.", "..........")
        val model = CropModel(2f, CropModelWeights(maxWindows = 1))
        // Costly everywhere except a window flush left: it mustn't move the zoom.
        val extra = cost { c -> if (c.left <= 0) 0.0 else 50.0 }
        val plain = model.prepare(listOf(m)).auto()
        val placed = model.prepare(listOf(m), listOf(extra)).auto()
        assertEquals(plain.scale, placed.scale)
        assertTrue(placed.pages[0].cells.left <= 0)
    }

    @Test fun `invalid extra penalties fail rather than poison solver costs`() = runBlocking {
        val m = mask("##", "##")
        for (v in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val prepared = CropModel(1f).prepare(listOf(m), listOf(cost { v }))
            try { prepared.solve(0.5f); fail("accepted $v") } catch (_: IllegalArgumentException) { }
        }
    }
}
