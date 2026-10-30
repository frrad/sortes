package dev.sortes.app.pdf

import android.graphics.Bitmap
import dev.sortes.app.zoom.PageRenderer
import dev.sortes.app.zoom.Region

/** The pages of a document, as the reader needs them: pixels for the crop pipeline, and regions to show. */
interface PageSource : PageRenderer {
    /** Renders [region] of page [index] (in page points) to fill [width] x [height] pixels. */
    suspend fun renderRegion(index: Int, region: Region, width: Int, height: Int): Bitmap
}
