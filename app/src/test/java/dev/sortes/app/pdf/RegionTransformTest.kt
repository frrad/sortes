package dev.sortes.app.pdf

import dev.sortes.app.zoom.Region
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegionTransformTest {

    @Test
    fun regionCornersMapToBitmapCorners() {
        val region = Region(left = 50f, top = -12f, width = 300f, height = 400f)

        val points = floatArrayOf(50f, -12f, 350f, 388f, 200f, 188f)
        regionTransform(region, width = 600, height = 800).mapPoints(points)

        assertArrayEquals(floatArrayOf(0f, 0f, 600f, 800f, 300f, 400f), points, 1e-3f)
    }
}
