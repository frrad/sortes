package dev.sortes.app.ui

import dev.sortes.app.importing.ShareRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLandingTest {

    @Test
    fun folderLinkGoesToFolderSources() {
        assertTrue(ShareLanding.after(null, ShareRequest.AlphaxivFolder("f")).toFolderSources)
    }

    @Test
    fun papersGoToTheList() {
        assertFalse(ShareLanding.after(null, ShareRequest.Arxiv("1706.03762")).toFolderSources)
        assertFalse(ShareLanding.after(null, ShareRequest.Pdf("content://x")).toFolderSources)
    }

    @Test
    fun repeatedSharesAreDistinct() {
        val first = ShareLanding.after(null, ShareRequest.AlphaxivFolder("f"))
        val second = ShareLanding.after(first, ShareRequest.AlphaxivFolder("f"))

        assertEquals(1, first.seq)
        assertEquals(2, second.seq)
        // Distinct values, so the UI reacts to the second share too.
        assertNotEquals(first, second)
    }
}
