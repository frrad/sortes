package dev.sortes.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Call from inside a dialog's content to stop the dialog window greying out the screen behind
 * it. On e-ink the dim is a grey fill over everything; the dialog's outline marks it instead.
 */
@Composable
internal fun NoDialogDim() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(0f) }
}
