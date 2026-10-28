package dev.sortes.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.sortes.app.pdf.PageSource
import dev.sortes.app.zoom.FullPage
import dev.sortes.app.zoom.PageSize
import dev.sortes.app.zoom.Placement
import dev.sortes.app.zoom.Region
import dev.sortes.app.zoom.regionOn
import kotlin.math.roundToInt

/**
 * Page [index] whole, for judging a crop: the crop window [placement] puts on it drawn as a bold
 * frame, and each piece of ink it cuts off in a dashed box. Outside the frame stays plain paper.
 * The page is rendered as the reader renders it, only showing the whole of it, at a size that fits.
 */
@Composable
internal fun CropPreview(
    pages: PageSource,
    index: Int,
    /** The screen's width / height: the whole page is shown padded to it, as with no zoom. */
    aspect: Float,
    placement: Placement,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // A margin for the outlines, which are drawn outside what they mark.
        val margin = with(LocalDensity.current) { PREVIEW_MARGIN.roundToPx() }
        val (width, height) = fitAspect(constraints.maxWidth - 2 * margin, constraints.maxHeight - 2 * margin, aspect)
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        val preview by produceState<Result<Rendered>?>(null, pages, index, aspect, width, height) {
            value = null // Never show another page's preview with this one's frame.
            value = catching {
                val page = pages.pageSize(index)
                val shown = FullPage.regionOn(page, aspect)
                Rendered(pages.renderRegion(index, shown, width, height), page, shown)
            }
        }
        preview?.onSuccess { (bitmap, page, shown) ->
            val ink = LocalContentColor.current
            val density = LocalDensity.current
            // Covers whatever was shown here before.
            Box(Modifier.fillMaxSize().background(ReaderPaper), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                        .testTag("cropPreview"),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}, whole",
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Outlines go outside their boxes, so they never cover the ink they mark.
                    Marker(placement.window.regionOn(page, aspect).toPixels(shown, width, height), "cropFrame") {
                        val stroke = FRAME_STROKE.toPx()
                        val r = Rect(Offset.Zero, size).inflate(stroke / 2)
                        drawRect(ink, r.topLeft, r.size, style = Stroke(stroke))
                    }
                    for (cut in placement.cut) {
                        Marker(cut.region.toPixels(shown, width, height), "cutInk") {
                            val stroke = CUT_STROKE.toPx()
                            val r = Rect(Offset.Zero, size).inflate(CUT_PADDING.toPx() + stroke / 2)
                            val dash = CUT_DASH.toPx()
                            drawRect(
                                ink, r.topLeft, r.size,
                                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
                            )
                        }
                    }
                }
            }
        }?.onFailure {
            Message(
                "Couldn't render page ${index + 1}: ${it.message ?: it}",
                Modifier.background(ReaderPaper),
            )
        }
    }
}

private data class Rendered(val bitmap: Bitmap, val page: PageSize, val shown: Region)

/** An invisible box laid out over [rect] (in pixels of its parent) that draws [draw] around itself. */
@Composable
private fun Marker(rect: Rect, tag: String, draw: DrawScope.() -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .drawBehind(draw)
            .testTag(tag),
    )
}

/** The largest [width] / [height] = [aspect] size, in whole pixels, that fits [maxWidth] x [maxHeight]. */
internal fun fitAspect(maxWidth: Int, maxHeight: Int, aspect: Float): Pair<Int, Int> =
    if (maxWidth.toFloat() / maxHeight > aspect) {
        (maxHeight * aspect).roundToInt().coerceAtMost(maxWidth) to maxHeight
    } else {
        maxWidth to (maxWidth / aspect).roundToInt().coerceAtMost(maxHeight)
    }

/** This region of a page, in pixels of a [width] x [height] bitmap that shows [shown] of it. */
internal fun Region.toPixels(shown: Region, width: Int, height: Int): Rect {
    val sx = width / shown.width
    val sy = height / shown.height
    return Rect(
        left = (left - shown.left) * sx,
        top = (top - shown.top) * sy,
        right = (left + this.width - shown.left) * sx,
        bottom = (top + this.height - shown.top) * sy,
    )
}

private val PREVIEW_MARGIN = 8.dp
private val FRAME_STROKE = 3.dp
private val CUT_STROKE = 1.5.dp
private val CUT_DASH = 4.dp
/** Room between cut-off ink and the dashed box around it. */
private val CUT_PADDING = 2.dp
