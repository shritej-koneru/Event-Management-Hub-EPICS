package com.example.eventmanagementhub.ui.theme

import androidx.compose.ui.graphics.Color

// "Campus ops" design tokens — see docs/DESIGN_SYSTEM.md. One accent (teal), warm paper,
// hairline borders. Status colours are reserved for status.

val Paper = Color(0xFFF7F5F0)
val HubSurface = Color(0xFFFFFFFF)
val SurfaceSunken = Color(0xFFF0EDE6)
val Line = Color(0xFFE3DFD6)
val LineStrong = Color(0xFFCFC9BC)

val Ink = Color(0xFF1C1F1E)
val Ink2 = Color(0xFF4A4F4C)
val Ink3 = Color(0xFF6B706C)

val Accent = Color(0xFF0F6E6E)
val AccentHover = Color(0xFF0B5858)
val AccentSoft = Color(0xFFE3F0EE)
val OnAccent = Color(0xFFFFFFFF)

val Success = Color(0xFF2E7D4F)
val SuccessSoft = Color(0xFFE4F2E9)
val Warning = Color(0xFF8F540C)
val WarningSoft = Color(0xFFFBEFDC)
val Danger = Color(0xFFB3261E)
val DangerSoft = Color(0xFFFBE7E5)
val Info = Color(0xFF2F5D8A)
val InfoSoft = Color(0xFFE6EEF6)

// Event category dots
val CategoryTech = Info
val CategoryCultural = Color(0xFF8A3B6B)
val CategoryEntertainment = Warning
val CategoryWellness = Success
val CategoryCareer = Accent
val CategorySports = Danger
val CategoryOther = Ink3

// Map
val MapWalkway = Color(0xFF9AA19C)
val MapRoute = Accent
val MapStart = Success
val MapDestination = Danger
val MapGrid = Line.copy(alpha = 0.4f)
val MapBounds = Line
