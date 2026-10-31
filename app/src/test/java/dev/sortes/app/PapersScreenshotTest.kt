package dev.sortes.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.ImportStatus
import dev.sortes.app.ui.AddPaperDialog
import dev.sortes.app.ui.OVERFLOW_MENU_TAG
import dev.sortes.app.ui.PapersList
import dev.sortes.app.ui.theme.SortesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PapersScreenshotTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun papersList() {
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE)
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/papers_list.png")
    }

    // The theme ignores the system theme: this should match papers_list.png.
    @Test
    @Config(qualifiers = "night")
    fun papersListInDarkSystemTheme() {
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE)
            }
        }
        composeRule
            .onRoot()
            .captureRoboImage("build/outputs/roborazzi/papers_list_dark_system_theme.png")
    }

    @Test
    fun addPaperDialogScreenshot() {
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE)
                AddPaperDialog(onImportArxiv = {}, onPickPdf = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Add paper").assertExists()
        // The dialog is its own window, so capture the whole screen rather than the root node.
        captureScreenRoboImage("build/outputs/roborazzi/add_paper_dialog.png")
    }

    @Test
    fun papersListWithImportStatus() {
        composeRule.setContent {
            SortesTheme {
                PapersList(
                    papers = SAMPLE,
                    importStatus = ImportStatus.Failed("Not an arXiv ID or URL"),
                )
            }
        }
        composeRule.onNodeWithText("Import failed: Not an arXiv ID or URL").assertExists()
        composeRule
            .onRoot()
            .captureRoboImage("build/outputs/roborazzi/papers_list_import_failed.png")
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
                PapersList(
                    papers = SAMPLE,
                    importStatus = status,
                    onDismissImportStatus = { status = null },
                )
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
                AddPaperDialog(
                    onImportArxiv = { imported = it },
                    onPickPdf = { picked = true },
                    onDismiss = {},
                )
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
    fun menuOpensFolderSourcesAndCloses() {
        var opened = false
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = SAMPLE, onOpenFolderSources = { opened = true })
            }
        }
        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertExists()
        // Nothing to sync without folders, so no item for it.
        composeRule.onNodeWithText("Sync all folders").assertDoesNotExist()
        composeRule.onNodeWithText("Folder sources…").performClick()

        assertTrue(opened)
        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertDoesNotExist()
    }

    @Test
    fun menuSyncsAllFolders() {
        var synced = 0
        var opened = false
        composeRule.setContent {
            SortesTheme {
                PapersList(
                    papers = SAMPLE,
                    onOpenFolderSources = { opened = true },
                    onSyncAllFolders = { synced++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Sync all folders").performClick()

        assertEquals(1, synced)
        assertFalse(opened)
        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertDoesNotExist()
    }

    @Test
    fun menuClosesOnBack() {
        composeRule.setContent {
            SortesTheme { PapersList(papers = SAMPLE) }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertExists()

        Espresso.pressBack()

        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Papers").assertExists()
    }

    @Test
    fun menuScreenshot() {
        composeRule.setContent {
            SortesTheme { PapersList(papers = SAMPLE, onSyncAllFolders = {}) }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Folder sources…").assertExists()
        // The menu is its own window, so capture the whole screen rather than the root node.
        captureScreenRoboImage("build/outputs/roborazzi/papers_menu.png")
    }

    @Test
    fun statusLinesThatAppearLaterAreOnScreen() {
        // A list already showing papers: a line added above them must not land above the viewport.
        var syncing by mutableStateOf(false)
        var status by mutableStateOf<ImportStatus?>(null)
        composeRule.setContent {
            SortesTheme {
                PapersList(papers = MANY, syncingFolders = syncing, importStatus = status)
            }
        }
        composeRule.onNodeWithText(MANY.first().title).assertIsDisplayed()

        syncing = true
        status = ImportStatus.Running(1)

        composeRule.onNodeWithText("Syncing folders…").assertIsDisplayed()
        composeRule.onNodeWithText("Importing…").assertIsDisplayed()

        status = ImportStatus.Failed("No network")
        composeRule.onNodeWithText("Import failed: No network").assertIsDisplayed()

        syncing = false
        composeRule.onNodeWithText("Syncing folders…").assertDoesNotExist()
    }

    @Test
    fun folderSyncFailureShowsAndLeadsToFolderSources() {
        var failed by mutableStateOf(false)
        var opened = false
        composeRule.setContent {
            SortesTheme {
                PapersList(
                    papers = MANY,
                    folderSyncFailed = failed,
                    onOpenFolderSources = { opened = true },
                )
            }
        }
        composeRule.onNodeWithText("Folder sync failed", substring = true).assertDoesNotExist()

        failed = true

        composeRule
            .onNodeWithText("Folder sync failed — see Folder sources")
            .assertIsDisplayed()
            .performClick()
        assertTrue(opened)
    }

    @Test
    fun statusLinesScreenshot() {
        composeRule.setContent {
            SortesTheme {
                PapersList(
                    papers = SAMPLE,
                    syncingFolders = true,
                    folderSyncFailed = true,
                    importStatus = ImportStatus.Running(1),
                )
            }
        }
        composeRule
            .onRoot()
            .captureRoboImage("build/outputs/roborazzi/papers_list_status_lines.png")
    }

    private companion object {
        val MANY =
            (1L..8L).map {
                Paper(
                    id = it,
                    title = "Paper $it",
                    authors = listOf("Author $it"),
                    abstract = "Abstract $it.",
                    year = 2020,
                    venue = null,
                )
            }

        val SAMPLE =
            listOf(
                Paper(
                    id = 1,
                    title = "Attention Is All You Need",
                    authors = listOf("Ashish Vaswani", "Noam Shazeer", "Niki Parmar"),
                    abstract =
                        "We propose a new simple network architecture, the " +
                            "Transformer, based solely on attention mechanisms.",
                    year = 2017,
                    venue = "NeurIPS",
                ),
                Paper(
                    id = 2,
                    title = "Deep Residual Learning for Image Recognition",
                    authors = listOf("Kaiming He", "Xiangyu Zhang", "Shaoqing Ren"),
                    abstract =
                        "We present a residual learning framework to ease the " +
                            "training of substantially deeper networks.",
                    year = 2016,
                    venue = "CVPR",
                ),
            )
    }
}
