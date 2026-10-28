package dev.sortes.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sortes.app.data.FolderSource
import dev.sortes.app.ui.SourcesList
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
class SourcesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val folderId = "01a1838a-db89-71d0-956c-e8067fdfee2a"
    private val link = "https://www.alphaxiv.org/shared/folder/$folderId"

    @Test
    fun sourcesList() {
        composeRule.setContent {
            SortesTheme { SourcesList(sources = SAMPLE, syncing = setOf(2L)) }
        }
        composeRule.onNodeWithText("Sortes").assertExists()
        composeRule.onNodeWithText("Syncing…").assertExists()
        composeRule.onNodeWithText("Never synced").assertDoesNotExist()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/sources_list.png")
    }

    @Test
    fun emptyState() {
        composeRule.setContent {
            SortesTheme { SourcesList(sources = emptyList(), syncing = emptySet()) }
        }
        composeRule.onNodeWithText("No sources yet", substring = true).assertExists()
        composeRule.onNodeWithText("Sync all").assertIsNotEnabled()
        composeRule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun addingValidLinkPassesFolderIdAndClearsField() {
        val added = mutableListOf<String>()
        composeRule.setContent {
            SortesTheme { SourcesList(sources = emptyList(), syncing = emptySet(), onAdd = { added += it }) }
        }

        composeRule.onNodeWithText("alphaXiv folder link").performTextInput("Look: $link")
        composeRule.onNodeWithText("Add").performClick()

        assertEquals(listOf(folderId), added)
        composeRule.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun invalidLinkShowsErrorAndIsNotAdded() {
        val added = mutableListOf<String>()
        composeRule.setContent {
            SortesTheme { SourcesList(sources = emptyList(), syncing = emptySet(), onAdd = { added += it }) }
        }

        composeRule.onNodeWithText("alphaXiv folder link").performTextInput("https://arxiv.org/abs/1706.03762")
        composeRule.onNodeWithText("Add").performClick()

        assertTrue(added.isEmpty())
        composeRule.onNodeWithText("Not an alphaXiv shared-folder link").assertExists()
    }

    @Test
    fun syncAndRemoveButtons() {
        val synced = mutableListOf<Long>()
        val removed = mutableListOf<Long>()
        var syncedAll = false
        composeRule.setContent {
            SortesTheme {
                SourcesList(
                    sources = SAMPLE.take(1),
                    syncing = emptySet(),
                    onSync = { synced += it },
                    onRemove = { removed += it },
                    onSyncAll = { syncedAll = true },
                )
            }
        }

        composeRule.onNodeWithText("Sync").performClick()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.onNodeWithText("Sync all").performClick()

        assertEquals(listOf(1L), synced)
        assertEquals(listOf(1L), removed)
        assertTrue(syncedAll)
    }

    @Test
    fun syncButtonIsDisabledWhileSyncing() {
        composeRule.setContent {
            SortesTheme { SourcesList(sources = SAMPLE.take(1), syncing = setOf(1L)) }
        }
        composeRule.onNodeWithText("Sync").assertIsNotEnabled()
        composeRule.onNodeWithText("Sync all").assertIsNotEnabled()
    }

    @Test
    fun neverSyncedSourceShowsPlaceholderName() {
        composeRule.setContent {
            SortesTheme { SourcesList(sources = listOf(FolderSource(id = 3, folderId = folderId)), syncing = emptySet()) }
        }
        composeRule.onNodeWithText("alphaXiv folder").assertExists()
        composeRule.onNodeWithText("Never synced").assertExists()
        composeRule.onNodeWithText("Sync").assertIsEnabled()
    }

    @Test
    fun errorIsShownAndDismissable() {
        var dismissed = false
        composeRule.setContent {
            SortesTheme {
                SourcesList(
                    sources = SAMPLE,
                    syncing = emptySet(),
                    error = "Couldn't save source: disk I/O error",
                    onDismissError = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("Couldn't save source: disk I/O error").performClick()

        assertTrue(dismissed)
    }

    @Test
    fun backButton() {
        var back = false
        composeRule.setContent {
            SortesTheme { SourcesList(sources = emptyList(), syncing = emptySet(), onBack = { back = true }) }
        }
        composeRule.onNodeWithText("Back").performClick()
        assertTrue(back)
    }

    private companion object {
        val SAMPLE = listOf(
            FolderSource(
                id = 1,
                folderId = "01a1838a-db89-71d0-956c-e8067fdfee2a",
                name = "Sortes",
                lastSyncedAt = 1_798_000_000_000,
                lastResult = "3 added, 1 already in library",
            ),
            FolderSource(id = 2, folderId = "01a1838a-db89-71d0-956c-e8067fdfee2b", name = "Reading group"),
        )
    }
}
