package dev.sortes.app.pdf

import android.graphics.Bitmap
import dev.sortes.app.zoom.InkMask
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.Region

/** The pages of a document, as the reader needs them. */
interface PageSource {
    val pageCount: Int

    suspend fun pageSize(index: Int): PageSize

    /** Renders page [index] squashed into [width] x [height] pixels and marks where its ink is. */
    suspend fun renderMask(index: Int, width: Int, height: Int): InkMask

    /** Renders [region] of page [index] (in page points) to fill [width] x [height] pixels. */
    suspend fun renderRegion(index: Int, region: Region, width: Int, height: Int): Bitmap
}
