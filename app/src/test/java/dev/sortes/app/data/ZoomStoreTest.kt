package dev.sortes.app.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ZoomStoreTest {

    private fun store() = ZoomStore(ApplicationProvider.getApplicationContext<android.content.Context>())

    @Test
    fun papersStartOnAutomaticZoom() {
        assertNull(store().get(1))
    }

    @Test
    fun remembersAChosenZoomPerPaper() {
        store().set(1, 0.8f)
        store().set(2, 0.65f)

        // A fresh instance reads back what an earlier one saved.
        assertEquals(0.8f, store().get(1))
        assertEquals(0.65f, store().get(2))
        assertNull(store().get(3))
    }

    @Test
    fun clearingGoesBackToAutomatic() {
        store().set(1, 0.8f)

        store().set(1, null)

        assertNull(store().get(1))
    }
}
