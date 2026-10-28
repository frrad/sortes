package dev.sortes.app.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Black on white, with no tints, so the app reads the same on colour and monochrome e-ink.
 * Every role that Material draws as a fill (surfaces, containers, tonal elevation) is white and
 * every role it draws as text, icon or outline is black. There is no dark variant and no
 * dynamic colour: the system theme and wallpaper don't change anything.
 */
internal val InkColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Paper,
    onPrimaryContainer = Ink,
    inversePrimary = Paper,
    secondary = Ink,
    onSecondary = Paper,
    secondaryContainer = Paper,
    onSecondaryContainer = Ink,
    tertiary = Ink,
    onTertiary = Paper,
    tertiaryContainer = Paper,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Paper,
    onSurfaceVariant = Ink,
    surfaceTint = Paper,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    error = Ink,
    onError = Paper,
    errorContainer = Paper,
    onErrorContainer = Ink,
    outline = Ink,
    outlineVariant = Ink,
    scrim = Ink,
    surfaceBright = Paper,
    surfaceDim = Paper,
    surfaceContainer = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = Paper,
    surfaceContainerLow = Paper,
    surfaceContainerLowest = Paper,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = InkColorScheme,
        typography = Typography,
    ) {
        // No ripples: e-ink can't animate them and leaves ghosting behind.
        CompositionLocalProvider(LocalRippleConfiguration provides null, content = content)
    }
}
