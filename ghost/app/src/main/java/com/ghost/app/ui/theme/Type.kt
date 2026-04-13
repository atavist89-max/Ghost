package com.ghost.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ghost.app.R

// VT323 Terminal Font (1980s CRT aesthetic)
val VT323 = FontFamily(
    Font(R.font.vt323_regular, FontWeight.Normal)
)

// Xanti Typewriter Font Family (legacy, kept for reference)
val XantiTypewriter = FontFamily(
    Font(R.font.xanti_typewriter_regular, FontWeight.Normal)
)

// Pip-Boy Terminal Typography - VT323 for authentic CRT look
val GhostTypography = Typography(
    // Body text - Primary response text
    bodyLarge = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
        color = GhostColors.textPhosphor
    ),
    bodyMedium = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.1.sp,
        color = GhostColors.textPhosphor
    ),
    bodySmall = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.1.sp,
        color = GhostColors.textPhosphorDim
    ),
    // Titles - Headers and labels
    titleLarge = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.sp,
        color = GhostColors.phosphorGreen
    ),
    titleMedium = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorGreen
    ),
    titleSmall = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorDim
    ),
    // Labels - Small UI elements
    labelLarge = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.25.sp,
        color = GhostColors.textPhosphorDim
    ),
    labelMedium = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.25.sp,
        color = GhostColors.textPhosphorDim
    ),
    labelSmall = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        letterSpacing = 0.25.sp,
        color = GhostColors.textPhosphorDim
    ),
    // Display - Large text elements
    displayLarge = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 1.sp,
        color = GhostColors.phosphorGreen
    ),
    displayMedium = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorGreen
    ),
    displaySmall = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorBright
    ),
    // Headlines - Section headers
    headlineLarge = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorGreen
    ),
    headlineMedium = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorGreen
    ),
    headlineSmall = TextStyle(
        fontFamily = VT323,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        color = GhostColors.phosphorDim
    )
)

// Typewriter font style for AI responses (legacy, now uses VT323)
val TypewriterResponseStyle = TextStyle(
    fontFamily = VT323,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.1.sp,
    color = GhostColors.textPhosphor
)
