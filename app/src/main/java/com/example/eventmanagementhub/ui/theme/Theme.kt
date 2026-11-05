package com.example.eventmanagementhub.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Accent,
    secondary = Accent,
    onSecondary = OnAccent,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = Ink,
    tertiary = Info,
    onTertiary = OnAccent,
    tertiaryContainer = InfoSoft,
    onTertiaryContainer = Info,
    background = Paper,
    onBackground = Ink,
    surface = HubSurface,
    onSurface = Ink,
    surfaceVariant = SurfaceSunken,
    onSurfaceVariant = Ink2,
    surfaceTint = HubSurface,
    inverseSurface = Ink,
    inverseOnSurface = HubSurface,
    inversePrimary = AccentSoft,
    error = Danger,
    onError = OnAccent,
    errorContainer = DangerSoft,
    onErrorContainer = Danger,
    outline = LineStrong,
    outlineVariant = Line,
    scrim = Ink,
    surfaceBright = HubSurface,
    surfaceDim = Paper,
    surfaceContainerLowest = HubSurface,
    surfaceContainerLow = HubSurface,
    surfaceContainer = HubSurface,
    surfaceContainerHigh = HubSurface,
    surfaceContainerHighest = SurfaceSunken
)

/** 6dp controls, 8dp cards/sheets; nothing above 8dp. */
private val HubShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun EventManagementHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = HubTypography.material,
        shapes = HubShapes,
        content = content
    )
}
