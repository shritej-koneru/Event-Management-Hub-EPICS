package com.example.eventmanagementhub.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Base palette
val BodyBackground = Color(0xFFF6F5F2)
val CardBorder = Color(0xFFEAE6FD)
val PrimaryPurple = Color(0xFF6849EC)
val NavyText = Color(0xFF20204A)
val MutedText = Color(0xFF77758B)

// Icon box gradients (used by EventListCard / Home)
val IconPurpleGradient = Brush.linearGradient(listOf(Color(0xFF7C5CFC), Color(0xFF4F39D0)))
val IconTealGradient = Brush.linearGradient(listOf(Color(0xFF2DD4BF), Color(0xFF0F9B8E)))
val IconOrangeGradient = Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFF97316)))

// Card background white gradient (Person 1's EventCardWhiteGradient)
val EventCardWhiteGradient =
    Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFFDFBFF)))