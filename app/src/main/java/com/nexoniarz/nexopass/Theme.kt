package com.nexoniarz.nexopass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)
val Pink = Color(0xFFF472B6)

private val Dark = darkColorScheme(
    primary = Color(0xFFB69CFF),
    onPrimary = Color(0xFF24005A),
    primaryContainer = Color(0xFF4B2A9E),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Cyan,
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF0E4E5A),
    onSecondaryContainer = Color(0xFFB8F2FF),
    tertiary = Pink,
    onTertiary = Color(0xFF4A0029),
    background = Color(0xFF07080F),
    onBackground = Color(0xFFE6E1F2),
    surface = Color(0xFF0D0F1C),
    onSurface = Color(0xFFE6E1F2),
    surfaceVariant = Color(0xFF1E2135),
    onSurfaceVariant = Color(0xFFC6C2DA),
    surfaceContainerLow = Color(0xFF12142A),
    surfaceContainer = Color(0xFF171A31),
    surfaceContainerHigh = Color(0xFF1E2139),
    surfaceContainerHighest = Color(0xFF262A44),
    outline = Color(0xFF6E6A86),
    outlineVariant = Color(0xFF34364E),
    error = Color(0xFFFF8A9A),
    errorContainer = Color(0xFF5C1020),
    onErrorContainer = Color(0xFFFFDADF),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6236D9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF00788C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC2F1FA),
    onSecondaryContainer = Color(0xFF001F25),
    tertiary = Color(0xFFB0306E),
    onTertiary = Color.White,
    background = Color(0xFFF7F4FF),
    onBackground = Color(0xFF1B1A24),
    surface = Color(0xFFFBF9FF),
    onSurface = Color(0xFF1B1A24),
    surfaceVariant = Color(0xFFE6E0F4),
    onSurfaceVariant = Color(0xFF48455A),
    surfaceContainerLow = Color(0xFFF3EFFC),
    surfaceContainer = Color(0xFFEEE9F9),
    surfaceContainerHigh = Color(0xFFE8E3F5),
    surfaceContainerHighest = Color(0xFFE2DCF0),
    outline = Color(0xFF79758C),
    outlineVariant = Color(0xFFCAC4DC),
)

private val NexoShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Violet to cyan, used for the logo, headline and main buttons. */
val BrandBrush = Brush.linearGradient(listOf(Violet, Cyan))

@Composable
fun NexoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, shapes = NexoShapes) {
        // Screens sit on transparent backgrounds (so the particles show) and
        // translucent cards; Material can't pick a text color for those and
        // would fall back to black, so set it here for the whole app.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
