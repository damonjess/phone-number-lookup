package com.example.phone_number_lookup.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * A single dark scheme on purpose: this is a recon console, and a light pastel variant would
 * undercut the terminal language the rest of the UI is built around.
 */
private val CyberColors = darkColorScheme(
    primary = Phosphor,
    onPrimary = PhosphorInk,
    primaryContainer = PhosphorDim,
    onPrimaryContainer = PhosphorWash,
    secondary = Cyber,
    onSecondary = CyberInk,
    secondaryContainer = CyberDim,
    onSecondaryContainer = CyberWash,
    tertiary = Amber,
    onTertiary = AmberInk,
    tertiaryContainer = AmberDim,
    onTertiaryContainer = AmberWash,
    error = Alert,
    onError = AlertInk,
    errorContainer = AlertDim,
    onErrorContainer = AlertWash,
    background = Void,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = TextMuted,
    outline = Edge,
    outlineVariant = EdgeDim,
    scrim = Void,
    inverseSurface = TextPrimary,
    inverseOnSurface = Void,
    inversePrimary = PhosphorDim,
)

private val TerminalShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun PhonenumberlookupTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CyberColors,
        typography = Typography,
        shapes = TerminalShapes,
        content = content,
    )
}
