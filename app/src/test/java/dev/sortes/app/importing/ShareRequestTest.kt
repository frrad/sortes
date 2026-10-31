package dev.sortes.app.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareRequestTest {

    private val send = "android.intent.action.SEND"
    private val pdfUri = "content://com.example.files/doc/42"

    @Test
    fun sharedTextBecomesArxivRequest() {
        assertEquals(
            ShareRequest.Arxiv("https://arxiv.org/abs/1706.03762"),
            ShareRequest.from(send, "text/plain", "https://arxiv.org/abs/1706.03762", null),
        )
    }

    @Test
    fun sharedPdfBecomesPdfRequest() {
        assertEquals(
            ShareRequest.Pdf(pdfUri),
            ShareRequest.from(send, "application/pdf", null, pdfUri),
        )
    }

    @Test
    fun ignoresIncompleteOrUnrelatedIntents() {
        assertNull(ShareRequest.from("android.intent.action.MAIN", null, null, null))
        assertNull(ShareRequest.from(null, null, null, null))
        assertNull(ShareRequest.from(send, "text/plain", null, null))
        assertNull(ShareRequest.from(send, "text/plain", "  ", null))
        assertNull(ShareRequest.from(send, "application/pdf", null, null))
        assertNull(ShareRequest.from(send, "image/png", null, "content://x/1"))
        assertNull(ShareRequest.from("android.intent.action.VIEW", "application/pdf", null, pdfUri))
    }

    @Test
    fun onlyContentUrisAreAcceptedForPdfs() {
        assertEquals(
            ShareRequest.Pdf("CONTENT://com.example/doc/1"),
            ShareRequest.from(send, "application/pdf", null, "CONTENT://com.example/doc/1"),
        )
        listOf(
                "file:///data/data/dev.sortes.app/databases/papers.db",
                "file:///sdcard/Download/x.pdf",
                "https://example.com/x.pdf",
                "/sdcard/x.pdf",
                "",
            )
            .forEach { assertNull(it, ShareRequest.from(send, "application/pdf", null, it)) }
    }

    @Test
    fun launchIntentIsIgnoredWhenActivityIsRestored() {
        assertNull(ShareRequest.from(send, "application/pdf", null, pdfUri, restored = true))
        assertNull(ShareRequest.from(send, "text/plain", "1706.03762", null, restored = true))
    }

    @Test
    fun launchIntentIsIgnoredWhenRelaunchedFromRecents() {
        val flags = ShareRequest.FLAG_LAUNCHED_FROM_HISTORY or 0x10000000 // | NEW_TASK
        assertNull(ShareRequest.from(send, "text/plain", "1706.03762", null, flags = flags))
    }

    @Test
    fun unrelatedFlagsDoNotSuppressShare() {
        assertEquals(
            ShareRequest.Arxiv("1706.03762"),
            ShareRequest.from(send, "text/plain", "1706.03762", null, flags = 0x10000000),
        )
    }

    private val folderLink =
        "https://www.alphaxiv.org/shared/folder/01a1838a-db89-71d0-956c-e8067fdfee2a"

    @Test
    fun sharedAlphaxivFolderLinkBecomesFolderRequest() {
        assertEquals(
            ShareRequest.AlphaxivFolder("01a1838a-db89-71d0-956c-e8067fdfee2a"),
            ShareRequest.from(
                send,
                "text/plain",
                "Check out \"Sortes\" on alphaXiv: $folderLink",
                null,
            ),
        )
    }

    @Test
    fun folderLinkWinsOverArxivIdInSameText() {
        assertEquals(
            ShareRequest.AlphaxivFolder("01a1838a-db89-71d0-956c-e8067fdfee2a"),
            ShareRequest.from(send, "text/plain", "arXiv:1706.03762 and more in $folderLink", null),
        )
    }

    @Test
    fun alphaxivPaperLinkIsStillAnArxivRequest() {
        assertEquals(
            ShareRequest.Arxiv("https://www.alphaxiv.org/abs/2311.08105"),
            ShareRequest.from(send, "text/plain", "https://www.alphaxiv.org/abs/2311.08105", null),
        )
    }

    @Test
    fun folderShareIsIgnoredWhenRestored() {
        assertNull(ShareRequest.from(send, "text/plain", folderLink, null, restored = true))
    }
}
