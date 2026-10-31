package dev.sortes.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/**
 * Hides the status and navigation bars while this is in composition and shows them again when it
 * leaves. On stock Android a swipe from the screen edge brings the bars back temporarily, over the
 * content, but that's best-effort: on Boox firmware the swipe also runs a Boox gesture (Home,
 * Recents, ...) and leaves the app. Callers must provide their own way out, as [ReaderFrame] does.
 *
 * Returns true while the bars are on their way out: from the request until the window's insets
 * first report them hidden, or [HIDE_TIMEOUT_MS] at most. On the Boox Note Air5 C that takes about
 * half a second, so a screen laid out around the bars meanwhile is laid out twice, and the reader
 * would render (and on e-ink refresh) its page for each size.
 */
@Composable
fun HideSystemBars(): Boolean {
    val hiding = barsHiding(barsShowing = WindowInsets.systemBars.isNotEmpty())
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
    return hiding
}

/**
 * Whether bars asked to hide when this entered composition are still on their way out: until
 * [barsShowing] is first false, or [HIDE_TIMEOUT_MS] at most.
 */
@Composable
internal fun barsHiding(barsShowing: Boolean): Boolean {
    var hiding by remember { mutableStateOf(true) }
    LaunchedEffect(barsShowing) { if (!barsShowing) hiding = false }
    LaunchedEffect(Unit) {
        delay(HIDE_TIMEOUT_MS)
        hiding = false
    }
    return hiding
}

/**
 * Bars still showing this long after they were hidden are taken to be staying (multi-window, say).
 */
internal const val HIDE_TIMEOUT_MS = 2_000L

@Composable
private fun WindowInsets.isNotEmpty(): Boolean {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return getTop(density) != 0 ||
        getBottom(density) != 0 ||
        getLeft(density, direction) != 0 ||
        getRight(density, direction) != 0
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
