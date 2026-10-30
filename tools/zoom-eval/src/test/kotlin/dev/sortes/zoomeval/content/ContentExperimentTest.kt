package dev.sortes.zoomeval.content

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ContentExperimentTest {
    @get:Rule val temp = TemporaryFolder()

    private fun inputs(out: File): List<String> {
        val corpus = temp.newFile().apply { writeText("# Empty test corpus\n") }
        val labels = temp.newFolder()
        return listOf("--out=${out.path}", "--corpus=${corpus.path}", "--labels=${labels.path}")
    }

    @Test fun `an empty experiment fails instead of publishing a successful empty report`() {
        val out = temp.newFolder()
        val args = inputs(out)
        val error = assertThrows(IllegalArgumentException::class.java) { contentMain(args) }
        assertTrue(error.message!!.contains("No labelled digital-native papers"))
        assertFalse(File(out,"index.html").exists())
    }

    @Test fun `output write errors propagate`() {
        val out = temp.newFile()
        val args = inputs(out)
        assertThrows(IOException::class.java) { contentMain(args) }
    }

    @Test fun `invalid feature parameters fail before reading or rendering PDFs`() {
        val out = temp.newFolder()
        for (option in listOf("--factor=0", "--weights=-0.1", "--risks=NaN", "--render=maybe", "--gamma=-1")) {
            assertThrows(IllegalArgumentException::class.java) { contentMain(listOf("--out=${out.path}",option)) }
        }
        assertThrows(IllegalArgumentException::class.java) { contentMain(listOf("--unknown=1")) }
        assertFalse(File(out,"parameters.json").exists())
    }
}
