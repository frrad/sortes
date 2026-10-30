package dev.sortes.app.zoom

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CropModelPenaltyTest {
    private fun mask(vararg rows: String) = InkMask(rows[0].length, rows.size,
        rows.flatMap { it.map { c -> c == '#' } }.toBooleanArray())

    @Test fun `additional cost participates in solving rescoring and page assignment`() = runBlocking {
        val m = mask("##########", "..........", "#.#.#.#.#.", "..........", "..........")
        val weights = CropModelWeights(beta=0.0, epsilon=0.0, maxWindows=1)
        val model = CropModel(2f, weights, additionalPageCost = { _, c -> if (c.top >= 1) 0.0 else 10.0 })
        val c = model.solve(listOf(m), 0.5f)
        val rescored = model.score(listOf(m), c.pages.map { it.window })
        assertEquals(c.total, rescored.total, 1e-9)
        assertEquals((if (c.pages[0].cells.top >= 1) 0.0 else 10.0), c.additionalTerm, 1e-9)
        assertEquals(model.pageCost(c.pages[0]), c.total, 1e-9)
        assertEquals(c.total, model.pageCost(model.assign(m, c, m.inkCount.toDouble())), 1e-9)
        assertEquals(c.total, model.pageCost(model.ownCrop(m, 0.5f, m.inkCount.toDouble())), 1e-9)
        // Fixed-scale, one-window exhaustive oracle (different positions may tie).
        val p = PaddedPage(m, 2f)
        var best = Double.POSITIVE_INFINITY
        for (y in 0..p.height-p.windowHeight(0.5f)) for (x in 0..p.width-p.windowWidth(0.5f)) {
            best = minOf(best, model.score(listOf(m), listOf(p.windowAt(x,y,0.5f))).total)
        }
        assertEquals(best,c.total,1e-9)
        val plain = CropModel(2f, weights).solve(listOf(m),0.5f)
        val zero = CropModel(2f, weights, additionalPageCost={_,_->0.0}).solve(listOf(m),0.5f)
        assertEquals(plain, zero)
    }

    @Test fun `extra penalties also apply on blank pages and across multiple windows`() = runBlocking {
        val a = mask("........", "........", "........", "........", "........", "........", "........", "........")
        val b = mask("........", "........", "........", "........", "........", "........", "........", "........")
        val model = CropModel(1f, CropModelWeights(alpha=0.0,beta=0.0,epsilon=0.0,gamma=0.05),
            additionalPageCost={m,c -> if (c.left == (if (m === a) 0 else 4)) 0.0 else 100.0})
        val c = model.solve(listOf(a,b),0.5f)
        assertEquals(2,c.k)
        assertEquals(0,c.pages[0].cells.left)
        assertEquals(4,c.pages[1].cells.left)
        assertEquals(0.05,c.total,1e-9)
        assertEquals(c.total,model.score(listOf(a,b),c.pages.map { it.window }).total,1e-9)
    }

    @Test fun `invalid extra penalties fail rather than poison solver costs`() = runBlocking {
        val m = mask("##", "##")
        for (v in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val model = CropModel(1f, additionalPageCost={_,_->v})
            try { model.solve(listOf(m),0.5f); fail("accepted $v") } catch (_: IllegalArgumentException) { }
        }
    }
}
