package com.example.eventmanagementhub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = PrimaryPurple,
    background = BodyBackground,
    surface = BodyBackground,
    onBackground = NavyText,
    onSurface = NavyText
)

@Composable
fun EventManagementHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography(
            headlineMedium = HubTypography.headlineMedium,
            titleMedium = HubTypography.titleMedium,
            titleSmall = HubTypography.titleSmall,
            bodySmall = HubTypography.bodySmall,
            bodyMedium = HubTypography.bodyMedium,
            headlineSmall = HubTypography.headlineSmall
        ),
        content = content
    )
}