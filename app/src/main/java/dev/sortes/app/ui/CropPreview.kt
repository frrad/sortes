package dev.sortes.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
        val margin = with(LocalDensity.current) { PREVIEW_MARGIN.roundToPx() }
        val (width, height) =
            fitAspect(constraints.maxWidth - 2 * margin, constraints.maxHeight - 2 * margin, aspect)
        if (width <= 0 || height <= 0) return@BoxWithConstraints
        // Kept while a new size renders, so a panel that changes height doesn't blank the page.
        var preview by remember(pages, index, aspect) { mutableStateOf<Result<Rendered>?>(null) }
        LaunchedEffect(pages, index, aspect, width, height) {
            preview = catching {
                val page = pages.pageSize(index)
                val shown = FullPage.regionOn(page, aspect)
                Rendered(pages.renderRegion(index, shown, width, height), page, shown)
            }
        }
        preview
            ?.onSuccess { (bitmap, page, shown) ->
                val ink = LocalContentColor.current
                val density = LocalDensity.current
                val crop = placement.window.regionOn(page, aspect).toPixels(shown, width, height)
                val cuts = placement.cut.map { it.region.toPixels(shown, width, height) }
                // Covers whatever was shown here before.
                Box(
                    Modifier.fillMaxSize().background(ReaderPaper),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(
                                with(density) { width.toDp() },
                                with(density) { height.toDp() },
                            )
                            .testTag("cropPreview")
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Page ${index + 1}, whole",
                            // Scaled until a render at a new size replaces it.
                            modifier = Modifier.fillMaxSize(),
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val frameStroke = FRAME_STROKE.toPx()
                            // The frame is drawn just outside the crop, so it never covers kept
                            // ink.
                            val frame = crop.inflate(frameStroke)
                            val line = crop.inflate(frameStroke / 2)
                            drawRect(ink, line.topLeft, line.size, style = Stroke(frameStroke))
                            val cutStroke = CUT_STROKE.toPx()
                            val dash = CUT_DASH.toPx()
                            // Boxes are cut away where the frame is, so they never cover kept ink
                            // either.
                            clipRect(
                                frame.left,
                                frame.top,
                                frame.right,
                                frame.bottom,
                                ClipOp.Difference,
                            ) {
                                for (cut in cuts) {
                                    val box =
                                        cutBox(
                                                cut,
                                                crop,
                                                frame,
                                                CUT_PADDING.toPx(),
                                                CUT_REACH.toPx(),
                                            )
                                            .deflate(cutStroke / 2)
                                    drawRect(
                                        ink,
                                        box.topLeft,
                                        box.size,
                                        style =
                                            Stroke(
                                                cutStroke,
                                                pathEffect =
                                                    PathEffect.dashPathEffect(
                                                        floatArrayOf(dash, dash)
                                                    ),
                                            ),
                                    )
                                }
                            }
                        }
                        // Where the frame and boxes mark, for tests and accessibility tools.
                        Marker(crop, "cropFrame")
                        for (cut in cuts) Marker(cut, "cutInk")
                    }
                }
            }
            ?.onFailure {
                Message(
                    "Couldn't render page ${index + 1}: ${it.message ?: it}",
                    Modifier.background(ReaderPaper),
                )
            }
    }
}

private data class Rendered(val bitmap: Bitmap, val page: PageSize, val shown: Region)

/** An invisible box laid out over [rect], in pixels of its parent. */
@Composable
private fun Marker(rect: Rect, tag: String) {
    val density = LocalDensity.current
    Box(
        Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .testTag(tag)
    )
}

/**
 * The outline for cut-off ink at [cut], beside a [crop] whose frame reaches out to [frame]: [cut]
 * with [padding] around it, and on each side where the ink is past the crop, reaching at least
 * [reach] past the frame. Ink cut off by a sliver hides under the frame, so its box must stick out
 * from the frame to be seen.
 */
internal fun cutBox(cut: Rect, crop: Rect, frame: Rect, padding: Float, reach: Float): Rect {
    val box = cut.inflate(padding)
    return Rect(
        left = if (cut.left < crop.left) minOf(box.left, frame.left - reach) else box.left,
        top = if (cut.top < crop.top) minOf(box.top, frame.top - reach) else box.top,
        right = if (cut.right > crop.right) maxOf(box.right, frame.right + reach) else box.right,
        bottom =
            if (cut.bottom > crop.bottom) maxOf(box.bottom, frame.bottom + reach) else box.bottom,
    )
}

/**
 * The largest [width] / [height] = [aspect] size, in whole pixels, that fits [maxWidth] x
 * [maxHeight].
 */
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

/** Room around the page for the frame and boxes, which are drawn outside what they mark. */
private val PREVIEW_MARGIN = 12.dp
private val FRAME_STROKE = 3.dp
private val CUT_STROKE = 1.5.dp
private val CUT_DASH = 4.dp
/** Room between cut-off ink and the dashed box around it. */
private val CUT_PADDING = 2.dp

/** How far a box sticks out past the frame, at least, so a sliver hidden under the frame shows. */
private val CUT_REACH = 5.dp
