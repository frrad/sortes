package dev.sortes.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sortes.app.data.Paper
import dev.sortes.app.ui.AddPaperDialog
import dev.sortes.app.importing.ImportStatus
import dev.sortes.app.ui.PapersList
import dev.sortes.app.ui.theme.SortesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PapersScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun papersList() {
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE)
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/papers_list.png")
    }

    @Test
    fun papersListWithImportStatus() {
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE, importStatus = ImportStatus.Failed("Not an arXiv ID or URL"))
            }
        }
        composeRule.onNodeWithText("Import failed: Not an arXiv ID or URL").assertExists()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/papers_list_import_failed.png")
    }

    @Test
    fun overlappingImportsShowCount() {
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE, importStatus = ImportStatus.Running(2))
            }
        }
        composeRule.onNodeWithText("Importing… (2)").assertExists()
    }

    @Test
    fun importStatusIsDismissable() {
        var status by mutableStateOf<ImportStatus?>(ImportStatus.Done("Attention Is All You Need"))
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE, importStatus = status, onDismissImportStatus = { status = null })
            }
        }

        composeRule.onNodeWithText("Added: Attention Is All You Need").performClick()

        composeRule.onNodeWithText("Added: Attention Is All You Need").assertDoesNotExist()
    }

    @Test
    fun addPaperDialog() {
        var imported: String? = null
        var picked = false
        composeRule.setContent {
            SortesTheme {
                AddPaperDialog(onImportArxiv = { imported = it }, onPickPdf = { picked = true }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Import").assertIsNotEnabled()

        composeRule.onNodeWithText("arXiv ID or URL").performTextInput("1706.03762")
        composeRule.onNodeWithText("Import").performClick()
        composeRule.onNodeWithText("Pick PDF…").performClick()

        assertEquals("1706.03762", imported)
        assertTrue(picked)
    }

    @Test
    fun sourcesButtonOpensSources() {
        var opened = false
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE, onOpenSources = { opened = true })
            }
        }

        composeRule.onNodeWithText("Sources").performClick()

        assertTrue(opened)
    }

    private companion object {
        val SAMPLE = listOf(
            Paper(
                id = 1,
                title = "Attention Is All You Need",
                authors = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar"),
                abstract = "We propose a new simple network architecture, the " +
                    "Transformer, based solely on attention mechanisms.",
                year = 2017,
                venue = "NeurIPS",
            ),
            Paper(
                id = 2,
                title = "Deep Residual Learning for Image Recognition",
                authors = listOf("Kaiming He", "Xiangyu Zhang", "Shaoqing Ren"),
                abstract = "We present a residual learning framework to ease the " +
                    "training of substantially deeper networks.",
                year = 2016,
                venue = "CVPR",
            ),
        )
    }
}
