package dev.sortes.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sortes.app.ui.theme.SortesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The reader must stay black on white, so the back button is visible, in dark theme too. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderFrameDarkThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var contentColor = Color.Unspecified

    private fun setFailedReaderInDarkTheme() {
        composeRule.setContent {
            SortesTheme(darkTheme = true, dynamicColor = false) {
                // Like MainActivity: the Scaffold paints the dark theme's background.
                Scaffold { padding ->
                    ReaderFrame(onClose = {}, modifier = Modifier.padding(padding)) {
                        contentColor = LocalContentColor.current
                        Message("Couldn't open PDF: Unable to resolve host")
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // captureToImage() times out under Robolectric, so draw the window ourselves.
    private fun drawWindow(): Bitmap {
        val view = composeRule.activity.window.decorView
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            .also { view.draw(Canvas(it)) }
    }

    private fun Bitmap.color(x: Int, y: Int) = Color(getPixel(x, y))

    @Test
    fun contentIsBlack() {
        setFailedReaderInDarkTheme()
        assertEquals(Color.Black, contentColor)
    }

    @Test
    fun backgroundIsWhite() {
        setFailedReaderInDarkTheme()
        val window = drawWindow()
        assertEquals(Color.White, window.color(window.width / 2, window.height - 10))
    }

    @Test
    fun backButtonIsDarkOnLight() {
        setFailedReaderInDarkTheme()
        val bounds = composeRule.onNodeWithContentDescription("Back to papers")
            .fetchSemanticsNode().boundsInWindow
        val window = drawWindow()
        val luminances = (bounds.left.toInt() until bounds.right.toInt()).flatMap { x ->
            (bounds.top.toInt() until bounds.bottom.toInt()).map { y ->
                window.color(x, y).luminance()
            }
        }
        assertTrue("no dark arrow pixels", luminances.min() < 0.2f)
        assertTrue("no light background pixels", luminances.max() > 0.9f)
    }

    @Test
    fun screenshot() {
        setFailedReaderInDarkTheme()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/reader_failed_dark.png")
    }
}
