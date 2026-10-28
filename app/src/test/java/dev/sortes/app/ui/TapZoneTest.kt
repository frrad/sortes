package dev.sortes.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TapZoneTest {

    private fun zone(x: Float, y: Float) = tapZone(x, y, width = 300, height = 400)

    @Test
    fun leftAndRightHalvesTurnPages() {
        assertEquals(TapZone.Back, zone(10f, 10f))
        assertEquals(TapZone.Back, zone(149f, 200f))
        assertEquals(TapZone.Forward, zone(150f, 200f))
        assertEquals(TapZone.Forward, zone(290f, 390f))
    }

    @Test
    fun bottomMiddleOpensZoomControls() {
        assertEquals(TapZone.ZoomControls, zone(150f, 399f))
        assertEquals(TapZone.ZoomControls, zone(100f, 340f))
        assertEquals(TapZone.ZoomControls, zone(199f, 340f))
    }

    @Test
    fun zoomZoneIsOnlyTheMiddleThirdOfTheBottom() {
        assertEquals(TapZone.Back, zone(99f, 390f))
        assertEquals(TapZone.Forward, zone(200f, 390f))
        assertEquals(TapZone.Forward, zone(150f, 339f))
    }
}
