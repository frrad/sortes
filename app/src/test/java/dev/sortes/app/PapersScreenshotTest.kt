package dev.sortes.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sortes.app.data.Paper
import dev.sortes.app.ui.PapersList
import dev.sortes.app.ui.theme.SortesTheme
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
