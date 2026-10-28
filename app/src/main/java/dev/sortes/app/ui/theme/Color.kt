package dev.sortes.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Everything is drawn in black ink on white paper; see "Design for e-ink" in AGENTS.md. */
val Ink = Color.Black
val Paper = Color.White

/** The outline that marks a card, dialog or button, in place of a filled or shadowed surface. */
val InkBorder = BorderStroke(1.5.dp, Ink)
