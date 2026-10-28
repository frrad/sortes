package dev.sortes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.sortes.app.ui.theme.Ink
import dev.sortes.app.ui.theme.InkBorder
import dev.sortes.app.ui.theme.Paper

/**
 * A screen's title bar: an optional navigation button, the title, and actions at the end, over a
 * black rule. Flat and white, unlike Material's TopAppBar, which tints itself as content scrolls
 * under it.
 */
@Composable
internal fun InkTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 4.dp),
        ) {
            navigationIcon?.invoke()
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (navigationIcon == null) 12.dp else 4.dp),
            )
            actions()
        }
        HorizontalDivider(thickness = InkBorder.width, color = Ink)
    }
}

/** The ← button that leaves a screen; the same as the system Back. */
@Composable
internal fun BackArrow(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

internal const val OVERFLOW_MENU_TAG = "overflowMenu"

/**
 * A ⋮ button whose menu drops down below it. Unlike Material's DropdownMenu, the menu appears
 * without animation and is flat white with a black outline, with no shadow or elevation tint.
 * Nothing behind it is dimmed. Tapping outside it or pressing Back closes it; [items] get a
 * `close` to call when they're chosen.
 */
@Composable
internal fun OverflowMenu(items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val close = { expanded = false }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
        }
        if (expanded) {
            val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(margin) { BelowAnchorEnd(margin) },
                onDismissRequest = close,
                // Focusable, so Back and taps outside reach onDismissRequest.
                properties = PopupProperties(focusable = true),
            ) {
                val shape = RoundedCornerShape(4.dp)
                Column(
                    modifier = Modifier
                        .testTag(OVERFLOW_MENU_TAG)
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 200.dp)
                        .background(Paper, shape)
                        .border(InkBorder, shape)
                        .padding(vertical = 8.dp),
                ) {
                    items(close)
                }
            }
        }
    }
}

/**
 * Puts a popup under its anchor with their end edges lined up, kept [margin] pixels inside the
 * window, so its outline doesn't run into the screen edge or under the status bar: it shifts
 * rather than run off an edge, and goes above the anchor if there's no room below.
 */
internal class BelowAnchorEnd(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val width = popupContentSize.width
        val height = popupContentSize.height
        val x = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.right - width else anchorBounds.left
        val y = if (anchorBounds.bottom + height <= windowSize.height - margin || anchorBounds.top - height < margin) {
            anchorBounds.bottom
        } else {
            anchorBounds.top - height
        }
        return IntOffset(
            x.coerceIn(margin, (windowSize.width - width - margin).coerceAtLeast(margin)),
            y.coerceIn(margin, (windowSize.height - height - margin).coerceAtLeast(margin)),
        )
    }
}
