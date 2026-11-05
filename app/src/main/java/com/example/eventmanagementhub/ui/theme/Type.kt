package com.example.eventmanagementhub.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Spec type scale (system sans / Roboto), tabular figures everywhere:
 * Display 28/34 600 · Title 20/28 600 · Heading 16/24 600 · Body 14/22 400 ·
 * Body-strong 14/22 500 · Small 13/18 400 · Caption 12/16 500 · Overline 11/16 600.
 */
object HubTypography {
    private fun style(size: Int, line: Int, weight: FontWeight, tracking: TextUnit = 0.sp) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking,
        fontFeatureSettings = "tnum"
    )

    val display = style(28, 34, FontWeight.SemiBold)
    val title = style(20, 28, FontWeight.SemiBold)
    val heading = style(16, 24, FontWeight.SemiBold)
    val body = style(14, 22, FontWeight.Normal)
    val bodyStrong = style(14, 22, FontWeight.Medium)
    val small = style(13, 18, FontWeight.Normal)
    val caption = style(12, 16, FontWeight.Medium)
    /** Section labels only; callers upper-case the text. */
    val overline = style(11, 16, FontWeight.SemiBold, 0.66.sp)
    val button = style(14, 20, FontWeight.Medium)

    val material = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = display,
        headlineMedium = display,
        headlineSmall = title,
        titleLarge = title,
        titleMedium = heading,
        titleSmall = bodyStrong,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = small,
        labelLarge = button,
        labelMedium = caption,
        labelSmall = caption
    )
}
