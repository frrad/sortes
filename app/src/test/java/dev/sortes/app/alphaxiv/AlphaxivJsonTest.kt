package dev.sortes.app.alphaxiv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class AlphaxivJsonTest {

    private val folderId = "01a1838a-db89-71d0-956c-e8067fdfee2a"

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResource("/alphaxiv/$name")) { "missing fixture $name" }.readText()

    @Test
    fun parsesFolderNameFromFixture() {
        assertEquals("Sortes", AlphaxivJson.parseFolderName(fixture("shared_folder.json"), folderId))
    }

    @Test
    fun folderNameMatchesRequestedFolderNotFirstSubfolder() {
        val json = """{"folders":[{"id":"aaaa","name":"Child"},{"id":"$folderId","name":"Parent"}]}"""
        assertEquals("Parent", AlphaxivJson.parseFolderName(json, folderId))
    }

    @Test
    fun folderNameIsNullWhenMissingOrBlank() {
        assertNull(AlphaxivJson.parseFolderName("""{"folders":[]}""", folderId))
        assertNull(AlphaxivJson.parseFolderName("""{}""", folderId))
        assertNull(AlphaxivJson.parseFolderName("""{"folders":[{"id":"$folderId","name":null}]}""", folderId))
        assertNull(AlphaxivJson.parseFolderName("""{"folders":[{"id":"$folderId","name":"  "}]}""", folderId))
    }

    @Test
    fun parsesPapersFromFixture() {
        val page = AlphaxivJson.parsePapersPage(fixture("shared_folder_papers.json"))
        assertEquals(listOf("2311.08105", "2609.20807", "2602.03839", "2512.03442"), page.paperIds)
        assertNull(page.nextCursor)
    }

    @Test
    fun parsesNextCursor() {
        val page = AlphaxivJson.parsePapersPage("""{"papers":[{"universalId":"2401.00001"}],"nextCursor":"abc"}""")
        assertEquals(listOf("2401.00001"), page.paperIds)
        assertEquals("abc", page.nextCursor)
    }

    @Test
    fun skipsPapersWithoutUniversalId() {
        val json = """{"papers":[{"universalId":null},{"title":"x"},{"universalId":""},{"universalId":"2401.00001"}],"nextCursor":null}"""
        assertEquals(listOf("2401.00001"), AlphaxivJson.parsePapersPage(json).paperIds)
    }

    @Test
    fun emptyFolder() {
        val page = AlphaxivJson.parsePapersPage("""{"papers":[],"nextCursor":null}""")
        assertEquals(emptyList<String>(), page.paperIds)
    }

    @Test
    fun rejectsMissingPapersArray() {
        assertThrows(IOException::class.java) { AlphaxivJson.parsePapersPage("""{"error":{"message":"x"}}""") }
    }

    @Test
    fun rejectsInvalidJson() {
        assertThrows(IOException::class.java) { AlphaxivJson.parsePapersPage("<html>oops</html>") }
        assertThrows(IOException::class.java) { AlphaxivJson.parseFolderName("", folderId) }
    }
}
