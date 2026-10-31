package dev.sortes.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.ShareRequest
import dev.sortes.app.ui.theme.SortesTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Moving between the paper list, folder sources and the reader, as MainActivity wires them. */
@RunWith(RobolectricTestRunner::class)
class NavigationTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var papers by mutableStateOf(listOf(PAPER))
    private var share by mutableStateOf<ShareLanding?>(null)

    private fun show(restorationTester: StateRestorationTester? = null) {
        val content =
            @Composable {
                SortesTheme {
                    SortesNavigation(
                        papers = papers,
                        share = share,
                        paperList = { onOpenPaper, onOpenFolderSources ->
                            PapersList(
                                papers = papers,
                                onPaperClick = onOpenPaper,
                                onOpenFolderSources = onOpenFolderSources,
                            )
                        },
                        folderSources = { onBack ->
                            SourcesList(
                                sources = emptyList(),
                                syncing = emptySet(),
                                onBack = onBack,
                            )
                        },
                        reader = { paper, onClose ->
                            TextButton(onClick = onClose) { Text("Reading ${paper.title}") }
                        },
                    )
                }
            }
        if (restorationTester != null) restorationTester.setContent(content)
        else composeRule.setContent(content)
    }

    private fun openFolderSourcesFromMenu() {
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Folder sources…").performClick()
    }

    private fun systemBack() {
        // Let the last click's recomposition register its BackHandler first.
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun assertOnPaperList() {
        composeRule.onNodeWithText("Papers").assertExists()
        composeRule.onNodeWithText("Folder sources").assertDoesNotExist()
        composeRule.onNodeWithText("Reading", substring = true).assertDoesNotExist()
    }

    private fun assertOnFolderSources() {
        composeRule.onNodeWithText("Folder sources").assertExists()
        composeRule.onNodeWithText("Papers").assertDoesNotExist()
    }

    private fun assertReading() {
        composeRule.onNodeWithText("Reading ${PAPER.title}").assertExists()
    }

    @Test
    fun startsOnThePaperListWhereBackLeavesTheApp() {
        show()
        assertOnPaperList()
        composeRule.runOnUiThread {
            assertFalse(composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }

    @Test
    fun menuOpensFolderSourcesAndTheArrowComesBack() {
        show()

        openFolderSourcesFromMenu()
        assertOnFolderSources()

        composeRule.onNodeWithContentDescription("Back").performClick()
        assertOnPaperList()
    }

    @Test
    fun systemBackLeavesFolderSources() {
        show()
        openFolderSourcesFromMenu()

        systemBack()
        assertOnPaperList()
        composeRule.runOnUiThread {
            assertFalse(composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }

    @Test
    fun systemBackClosesTheReader() {
        show()
        composeRule.onNodeWithText(PAPER.title).performClick()
        assertReading()

        systemBack()

        assertOnPaperList()
    }

    @Test
    fun readerClosesToTheList() {
        show()
        composeRule.onNodeWithText(PAPER.title).performClick()

        composeRule.onNodeWithText("Reading ${PAPER.title}").performClick()

        assertOnPaperList()
    }

    @Test
    fun deletingTheOpenPaperReturnsToTheList() {
        show()
        composeRule.onNodeWithText(PAPER.title).performClick()

        papers = emptyList()

        assertOnPaperList()
    }

    @Test
    fun sharedFolderLinkLandsOnFolderSources() {
        show()

        share = ShareLanding.after(share, ShareRequest.AlphaxivFolder(FOLDER_ID))

        assertOnFolderSources()
    }

    @Test
    fun sharedFolderLinkClosesTheReader() {
        show()
        composeRule.onNodeWithText(PAPER.title).performClick()

        share = ShareLanding.after(share, ShareRequest.AlphaxivFolder(FOLDER_ID))

        assertOnFolderSources()
        // Back goes to the list, not back into the reader.
        systemBack()
        assertOnPaperList()
    }

    @Test
    fun sharedPaperLandsOnTheList() {
        show()
        openFolderSourcesFromMenu()

        share = ShareLanding.after(share, ShareRequest.Arxiv("1706.03762"))

        assertOnPaperList()
    }

    @Test
    fun sharedPdfClosesTheReader() {
        show()
        composeRule.onNodeWithText(PAPER.title).performClick()

        share = ShareLanding.after(share, ShareRequest.Pdf("content://downloads/1"))

        assertOnPaperList()
    }

    @Test
    fun secondFolderShareLandsAgainAfterGoingBack() {
        show()
        share = ShareLanding.after(share, ShareRequest.AlphaxivFolder(FOLDER_ID))
        assertOnFolderSources()
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertOnPaperList()

        share = ShareLanding.after(share, ShareRequest.AlphaxivFolder(FOLDER_ID))

        assertOnFolderSources()
    }

    @Test
    fun folderSourcesSurvivesRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        show(restorationTester)
        openFolderSourcesFromMenu()

        restorationTester.emulateSavedInstanceStateRestore()

        assertOnFolderSources()
    }

    @Test
    fun readerSurvivesRecreation() {
        val restorationTester = StateRestorationTester(composeRule)
        show(restorationTester)
        composeRule.onNodeWithText(PAPER.title).performClick()

        restorationTester.emulateSavedInstanceStateRestore()

        assertReading()
    }

    private companion object {
        const val FOLDER_ID = "01a1838a-db89-71d0-956c-e8067fdfee2a"
        val PAPER =
            Paper(
                id = 7,
                title = "Attention Is All You Need",
                authors = listOf("Ashish Vaswani"),
                abstract = "The Transformer.",
                year = 2017,
                venue = "NeurIPS",
            )
    }
}
