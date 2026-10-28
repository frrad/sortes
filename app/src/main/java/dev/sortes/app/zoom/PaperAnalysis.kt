package dev.sortes.app.zoom

import dev.sortes.app.pdf.PageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Renders every page of [source] at low resolution and analyzes crops for a screen of [aspect]. */
suspend fun analyzePaper(source: PageSource, aspect: Float): CropAnalysis {
    val sizes = List(source.pageCount) { source.pageSize(it) }
    val density = analysisDensity(sizes, aspect)
    val masks = sizes.mapIndexed { i, size ->
        val (width, height) = maskSize(size, density)
        source.renderMask(i, width, height)
    }
    return withContext(Dispatchers.Default) { CropAnalysis(masks, aspect) }
}
