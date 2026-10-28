package dev.sortes.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import dev.sortes.app.data.FolderSource
import dev.sortes.app.data.Paper
import dev.sortes.app.importing.ImportStatus
import dev.sortes.app.ui.theme.SortesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pins the e-ink look (see "Design for e-ink" in AGENTS.md): black text and outlines on white,
 * no colour and no grey fills, whatever the system theme. Run in light and dark system theme by
 * the subclasses below.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class InkColorsTest(private val systemDark: Boolean) {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var colorScheme: ColorScheme? = null

    /** Shows [content] as MainActivity does: in the theme, on the Scaffold's background. */
    private fun show(content: @Composable () -> Unit) {
        var dark: Boolean? = null
        composeRule.setContent {
            dark = isSystemInDarkTheme()
            SortesTheme {
                colorScheme = MaterialTheme.colorScheme
                Scaffold { padding ->
                    Box(Modifier.padding(padding)) { content() }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals("system dark theme", systemDark, dark)
    }

    @Test
    fun everyColorRoleIsBlackOrWhite() {
        show {}
        // ColorScheme's getters return the packed Color as a long (Color is a value class).
        val roles = ColorScheme::class.java.methods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.java }
        assertTrue("found no colour roles", roles.size > 20)
        for (role in roles) {
            val color = Color((role.invoke(colorScheme) as Long).toULong())
            assertTrue("${role.name} is $color", color == Color.Black || color == Color.White)
        }
    }

    @Test
    fun paperCardIsBlackOnWhite() {
        show { PapersList(papers = listOf(PAPER), importStatus = ImportStatus.Failed("No network")) }

        for (text in listOf(
            "Papers",
            "Import failed: No network",
            PAPER.title,
            PAPER.authors.joinToString(", "),
            "NeurIPS · 2017",
            PAPER.abstract,
        )) {
            assertEquals(text, Color.Black, composeRule.onNodeWithText(text, useUnmergedTree = true).textColor())
        }
        assertOutlinedInkCard(PAPER_CARD_TAG)
    }

    @Test
    fun sourceCardIsBlackOnWhite() {
        show { SourcesList(sources = listOf(SOURCE), syncing = emptySet(), error = "Couldn't sync") }

        for (text in listOf("Folder sources", "Couldn't sync", "Sortes", "Never synced", "Sync", "Remove")) {
            assertEquals(text, Color.Black, composeRule.onNodeWithText(text, useUnmergedTree = true).textColor())
        }
        assertOutlinedInkCard(SOURCE_CARD_TAG)
        assertInkIcon("Back")
    }

    @Test
    fun topBarIsBlackOnWhite() {
        show { PapersList(papers = emptyList()) }

        assertEquals(Color.Black, composeRule.onNodeWithText("Papers", useUnmergedTree = true).textColor())
        assertInkIcon("More options")
        // The rule under the bar is solid black, not a grey or tinted divider.
        val bar = composeRule.onNodeWithText("Papers").fetchSemanticsNode().boundsInWindow
        val window = drawWindow()
        val x = window.width / 2
        val rule = (bar.bottom.toInt()..bar.bottom.toInt() + 48).map { Color(window.getPixel(x, it)) }
        assertTrue("no black rule under the top bar: $rule", rule.any { it == Color.Black })
        assertTrue("coloured rule under the top bar: $rule", rule.all { it.red == it.green && it.green == it.blue })
    }

    @Test
    fun overflowMenuIsOutlinedInkOnWhite() {
        var menuView: android.view.View? = null
        show {
            OverflowMenu {
                menuView = LocalView.current
                DropdownMenuItem(text = { Text("Folder sources…") }, onClick = {})
                DropdownMenuItem(text = { Text("Sync all folders") }, onClick = {})
            }
        }
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.waitForIdle()

        for (text in listOf("Folder sources…", "Sync all folders")) {
            assertEquals(text, Color.Black, composeRule.onNodeWithText(text, useUnmergedTree = true).textColor())
        }
        // The menu is its own window: draw that, not the activity's.
        val popup = menuView!!.rootView
        val bitmap = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            .also { popup.draw(Canvas(it)) }
        composeRule.onNodeWithTag(OVERFLOW_MENU_TAG).assertExists()
        // The window is just the menu.
        assertOutlinedInk(bitmap, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
    }

    @Test
    fun addPaperDialogTextIsBlack() {
        show { AddPaperDialog(onImportArxiv = {}, onPickPdf = {}, onDismiss = {}) }

        for (text in listOf("Add paper", "Pick PDF…", "Cancel")) {
            assertEquals(text, Color.Black, composeRule.onNodeWithText(text, useUnmergedTree = true).textColor())
        }
    }

    @Test
    fun noDialogDimTurnsOffTheGreyWash() {
        var window: android.view.Window? = null
        composeRule.setContent {
            Dialog(onDismissRequest = {}) {
                NoDialogDim()
                window = (LocalView.current.parent as DialogWindowProvider).window
            }
        }
        composeRule.waitForIdle()
        assertEquals(0f, window!!.attributes.dimAmount)
    }

    /** The card is white inside, outlined in black, and has no colour or grey fill anywhere. */
    private fun assertOutlinedInkCard(tag: String) {
        val bounds = composeRule.onAllNodesWithTag(tag)[0].fetchSemanticsNode().boundsInWindow
        assertOutlinedInk(drawWindow(), bounds)
    }

    /** [bounds] of [window] are white inside, outlined in black, with no colour or grey fill. */
    private fun assertOutlinedInk(window: Bitmap, bounds: Rect) {
        val pixels = window.pixelsIn(bounds)

        val notGrey = pixels.filter { it.red != it.green || it.green != it.blue }
        assertTrue("coloured pixels in card, e.g. ${notGrey.take(3)}", notGrey.isEmpty())
        val background = pixels.groupingBy { it }.eachCount().maxBy { it.value }.key
        assertEquals("card background", Color.White, background)
        // Anything that isn't white is black ink or its anti-aliased edge, not a grey fill.
        val midGrey = pixels.count { it.luminance() in 0.3f..0.85f }
        assertTrue("$midGrey mid-grey pixels: a grey fill?", midGrey < pixels.size / 20)

        val y = bounds.center.y.toInt()
        val edge = (bounds.left.toInt()..bounds.left.toInt() + 2).map { Color(window.getPixel(it, y)) }
        assertTrue("no black outline at the card's left edge: $edge", edge.minOf { it.luminance() } < 0.2f)
    }

    /** The icon is drawn in black ink: no colour, and its darkest pixels are black. */
    private fun assertInkIcon(contentDescription: String) {
        val bounds = composeRule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInWindow
        val pixels = drawWindow().pixelsIn(bounds)
        val notGrey = pixels.filter { it.red != it.green || it.green != it.blue }
        assertTrue("coloured pixels in $contentDescription icon, e.g. ${notGrey.take(3)}", notGrey.isEmpty())
        assertTrue("$contentDescription icon isn't black", pixels.any { it == Color.Black })
    }

    // captureToImage() times out under Robolectric, so draw the window ourselves.
    private fun drawWindow(): Bitmap {
        val view = composeRule.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            .also { view.draw(Canvas(it)) }
    }

    private fun Bitmap.pixelsIn(bounds: Rect): List<Color> =
        (bounds.left.toInt() until bounds.right.toInt()).flatMap { x ->
            (bounds.top.toInt() until bounds.bottom.toInt()).map { y -> Color(getPixel(x, y)) }
        }

    /** The colour [androidx.compose.material3.Text] resolved for this text node. */
    private fun SemanticsNodeInteraction.textColor(): Color {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single().layoutInput.style.color
    }

    private companion object {
        val PAPER = Paper(
            id = 1,
            title = "Attention Is All You Need",
            authors = listOf("Ashish Vaswani", "Noam Shazeer"),
            abstract = "We propose a new simple network architecture, the Transformer.",
            year = 2017,
            venue = "NeurIPS",
        )
        val SOURCE = FolderSource(id = 1, folderId = "01a1838a-db89-71d0-956c-e8067fdfee2a", name = "Sortes")
    }
}

@RunWith(RobolectricTestRunner::class)
class InkColorsLightSystemThemeTest : InkColorsTest(systemDark = false)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "night")
class InkColorsDarkSystemThemeTest : InkColorsTest(systemDark = true)
