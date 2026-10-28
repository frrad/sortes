package dev.sortes.app.alphaxiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlphaxivFolderLinkTest {

    private val id = "01a1838a-db89-71d0-956c-e8067fdfee2a"

    @Test
    fun parsesCanonicalLink() {
        assertEquals(id, AlphaxivFolderLink.parse("https://www.alphaxiv.org/shared/folder/$id"))
    }

    @Test
    fun parsesWithoutSchemeOrWww() {
        assertEquals(id, AlphaxivFolderLink.parse("alphaxiv.org/shared/folder/$id"))
        assertEquals(id, AlphaxivFolderLink.parse("http://alphaxiv.org/shared/folder/$id"))
    }

    @Test
    fun parsesLinkEmbeddedInSharedText() {
        val text = "Check out my folder \"Sortes\" on alphaXiv:\nhttps://www.alphaxiv.org/shared/folder/$id"
        assertEquals(id, AlphaxivFolderLink.parse(text))
    }

    @Test
    fun ignoresTrailingQueryFragmentAndSlash() {
        assertEquals(id, AlphaxivFolderLink.parse("https://www.alphaxiv.org/shared/folder/$id/?ref=x#top"))
        assertEquals(id, AlphaxivFolderLink.parse("(https://www.alphaxiv.org/shared/folder/$id)"))
    }

    @Test
    fun normalisesCase() {
        assertEquals(id, AlphaxivFolderLink.parse("HTTPS://WWW.ALPHAXIV.ORG/shared/folder/${id.uppercase()}"))
    }

    @Test
    fun rejectsNonFolderLinks() {
        assertNull(AlphaxivFolderLink.parse("https://www.alphaxiv.org/abs/2311.08105"))
        assertNull(AlphaxivFolderLink.parse("https://arxiv.org/abs/2311.08105"))
        assertNull(AlphaxivFolderLink.parse("just some text"))
        assertNull(AlphaxivFolderLink.parse(""))
    }

    @Test
    fun rejectsOtherHosts() {
        assertNull(AlphaxivFolderLink.parse("https://evil.com/alphaxiv.org/shared/folder/$id"))
        assertNull(AlphaxivFolderLink.parse("https://alphaxiv.org.evil.com/shared/folder/$id"))
        assertNull(AlphaxivFolderLink.parse("https://notalphaxiv.org/shared/folder/$id"))
    }

    @Test
    fun rejectsMalformedIds() {
        assertNull(AlphaxivFolderLink.parse("https://www.alphaxiv.org/shared/folder/not-a-uuid"))
        assertNull(AlphaxivFolderLink.parse("https://www.alphaxiv.org/shared/folder/${id.dropLast(1)}"))
        assertNull(AlphaxivFolderLink.parse("https://www.alphaxiv.org/shared/folder/${id}0"))
    }

    @Test
    fun buildsCanonicalUrl() {
        assertEquals("https://www.alphaxiv.org/shared/folder/$id", AlphaxivFolderLink.url(id))
        assertEquals(id, AlphaxivFolderLink.parse(AlphaxivFolderLink.url(id)))
    }
}
