package dev.sortes.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sortes.app.zoom.CropAnalysis
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Manual zoom control: the paper's lost-ink curve with a slider along it. Left is the whole
 * page, right is zoomed in; the curve shows how much ink each zoom level cuts off.
 */
@Composable
fun ZoomPanel(
    analysis: CropAnalysis,
    scale: Float,
    isAuto: Boolean,
    onScaleChange: (Float) -> Unit,
    onAuto: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = analysis.scales.lastIndex
    var position by remember(analysis, scale) {
        mutableFloatStateOf(analysis.nearestIndex(scale).toFloat())
    }
    fun indexAt(position: Float) = position.roundToInt().coerceIn(0, last)
    val index = indexAt(position)
    val ink = LocalContentColor.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ReaderPaper)
            .border(1.dp, ink)
            // Swallow taps so they don't turn the page underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("zoomPanel"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = zoomSummary(analysis, index),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onAuto,
                enabled = !isAuto,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = ink,
                    disabledContentColor = ink.copy(alpha = 0.5f),
                ),
            ) {
                Text(if (isAuto) "Auto ✓" else "Auto")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(contentColor = ink),
            ) { Text("Close") }
        }
        LostInkCurve(
            analysis = analysis,
            selected = index,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(horizontal = 10.dp),
        )
        Slider(
            value = position,
            onValueChange = { position = it },
            // Read the position now: the slider calls this before recomposing with the new value.
            onValueChangeFinished = { onScaleChange(analysis.scales[indexAt(position)]) },
            valueRange = 0f..last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = ink,
                activeTrackColor = ink,
                inactiveTrackColor = ink.copy(alpha = 0.2f),
                activeTickColor = ink,
                inactiveTickColor = ink,
            ),
            modifier = Modifier.testTag("zoomSlider"),
        )
    }
}

private fun zoomSummary(analysis: CropAnalysis, index: Int): String {
    val zoom = "%.2f×".format(1f / analysis.scales[index])
    val lost = analysis.lostFraction(index)
    if (lost == 0f) return "Zoom $zoom · nothing cut off"
    val pages = analysis.pagesCropped(index)
    return "Zoom $zoom · cuts %s of ink on $pages page%s".format(
        percent(lost),
        if (pages == 1) "" else "s",
    )
}

private fun percent(fraction: Float): String = when {
    fraction < 0.001f -> "<0.1%"
    fraction < 0.1f -> "%.1f%%".format(fraction * 100)
    else -> "%.0f%%".format(fraction * 100)
}

/** Lowest lost-ink fraction the curve distinguishes from zero; the y axis is logarithmic. */
private const val CURVE_FLOOR = 1e-4f

/**
 * Lost ink against zoom. The y axis is logarithmic so the small step from trimming page numbers
 * and running heads is visible next to the steep rise from cutting into text.
 */
@Composable
private fun LostInkCurve(analysis: CropAnalysis, selected: Int, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val ink = LocalContentColor.current
    val faint = ink.copy(alpha = 0.25f)
    val muted = ink.copy(alpha = 0.5f)
    val labelStyle = TextStyle(fontSize = 9.sp, color = muted)
    val autoIndex = remember(analysis) { analysis.nearestIndex(analysis.autoScale) }
    Canvas(modifier.testTag("lostInkCurve")) {
        val last = analysis.scales.lastIndex.coerceAtLeast(1)
        fun x(i: Int) = size.width * i / last
        fun y(fraction: Float): Float {
            if (fraction <= CURVE_FLOOR) return size.height
            val t = (log10(fraction) - log10(CURVE_FLOOR)) / -log10(CURVE_FLOOR)
            return size.height * (1f - t.coerceIn(0f, 1f))
        }

        for ((fraction, label) in listOf(0.001f to "0.1%", 0.01f to "1%", 0.1f to "10%")) {
            val gy = y(fraction)
            drawLine(faint, Offset(0f, gy), Offset(size.width, gy))
            drawText(measurer, label, Offset(0f, gy - 12.sp.toPx()), labelStyle)
        }
        drawLine(muted, Offset(0f, size.height), Offset(size.width, size.height))

        val path = Path()
        for (i in analysis.scales.indices) {
            val p = Offset(x(i), y(analysis.lostFraction(i)))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, ink, style = Stroke(width = 2.dp.toPx()))

        drawLine(
            muted,
            Offset(x(autoIndex), 0f),
            Offset(x(autoIndex), size.height),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        drawText(measurer, "auto", Offset(x(autoIndex) + 2.dp.toPx(), 0f), labelStyle)
        drawLine(
            ink,
            Offset(x(selected), 0f),
            Offset(x(selected), size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}
