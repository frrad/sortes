package dev.sortes.app.importing

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.style.URLSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareIntentsTest {

    private fun send(type: String) = Intent(Intent.ACTION_SEND).setType(type)

    @Test
    fun readsStringText() {
        val intent = send("text/plain").putExtra(Intent.EXTRA_TEXT, "https://arxiv.org/abs/1706.03762")

        assertEquals(ShareRequest.Arxiv("https://arxiv.org/abs/1706.03762"), intent.toShareRequest(false))
    }

    @Test
    fun readsSpannedText() {
        val url = "https://arxiv.org/abs/1706.03762"
        val spanned = SpannableString("See $url").apply {
            setSpan(URLSpan(url), 4, length, 0)
        }
        val intent = send("text/plain").putExtra(Intent.EXTRA_TEXT, spanned)

        assertEquals(ShareRequest.Arxiv("See $url"), intent.toShareRequest(false))
    }

    @Test
    fun readsContentStream() {
        val uri = Uri.parse("content://com.example.files/doc/42")
        val intent = send("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)

        assertEquals(ShareRequest.Pdf(uri.toString()), intent.toShareRequest(false))
    }

    @Test
    fun rejectsFileStream() {
        val intent = send("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/dev.sortes.app/databases/papers.db"))

        assertNull(intent.toShareRequest(false))
    }

    @Test
    fun ignoresRestoredAndRecentsLaunches() {
        val intent = send("text/plain").putExtra(Intent.EXTRA_TEXT, "1706.03762")

        assertNull(intent.toShareRequest(restored = true))
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertNull(intent.toShareRequest(restored = false))
    }

    @Test
    fun inlinedRecentsFlagMatchesFramework() {
        assertEquals(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, ShareRequest.FLAG_LAUNCHED_FROM_HISTORY)
    }
}
