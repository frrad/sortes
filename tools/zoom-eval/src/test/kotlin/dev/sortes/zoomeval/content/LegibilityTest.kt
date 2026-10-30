package dev.sortes.zoomeval.content

import org.junit.Assert.*
import org.junit.Test

class LegibilityTest {
    @Test fun `linear is zeta times scale`() {
        assertEquals(0.4, Legibility.linear(0.5).cost(0.8, 20.0), 1e-12)
    }

    @Test fun `slope is zeta while text is small and diminishing but positive once big`() {
        val l = Legibility(zeta = 0.5, comfortable = 25.0, power = 4.0, floor = 0.1)
        val k = 20.0 // sT = 0.8
        fun slope(s: Double) = (l.cost(s + 1e-6, k) - l.cost(s - 1e-6, k)) / 2e-6
        assertEquals(0.5, slope(0.9), 1e-6)
        assertEquals(0.5 * Math.pow(0.7 / 0.8, 4.0), slope(0.7), 1e-6)
        // Far below sT the slope bottoms out at floor * zeta, never zero.
        assertEquals(0.05, slope(0.3), 1e-6)
        assertTrue(slope(0.3) > 0)
    }

    @Test fun `cost is continuous and increasing`() {
        val l = Legibility(0.5, 25.0, 8.0, 0.05)
        var prev = -1.0
        var s = 0.3
        while (s < 1.0) {
            val c = l.cost(s, 20.0)
            assertTrue(c > prev)
            if (prev >= 0) assertTrue(c - prev < 0.5 * 0.001 + 1e-9)
            prev = c
            s += 0.001
        }
    }

    @Test fun `parse`() {
        assertEquals(Legibility.linear(0.5), Legibility.parse("linear", 0.5))
        assertEquals(Legibility(0.5, 26.0, 8.0, 0.1), Legibility.parse("26:8:0.1", 0.5))
    }
}
