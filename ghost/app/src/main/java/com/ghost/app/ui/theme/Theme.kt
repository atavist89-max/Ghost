package com.ghost.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cyberpunk Phosphor Green Color Palette
private val PhosphorGreen = Color(0xFF39FF14)
private val PhosphorGreenDim = Color(0xFF2B8C1A)
private val PhosphorGreenBright = Color(0xFF5FFF3F)
private val GunmetalBackground = Color(0xFF0A0F0A)
private val GunmetalSurface = Color(0xFF141414)
private val GunmetalSurfaceVariant = Color(0xFF1A1A1A)
private val TextPhosphor = Color(0xFFE0FFE0)
private val TextPhosphorDim = Color(0xFF8FBC8F)
private val BorderPhosphor = Color(0xFF39FF14)
private val CrtScanline = Color(0xFF050A05)

// Dark color scheme - Cyberpunk terminal aesthetic
private val DarkColorScheme = darkColorScheme(
    primary = PhosphorGreen,
    onPrimary = GunmetalBackground,
    primaryContainer = PhosphorGreenDim.copy(alpha = 0.3f),
    onPrimaryContainer = PhosphorGreenBright,
    secondary = PhosphorGreenDim,
    onSecondary = GunmetalBackground,
    secondaryContainer = GunmetalSurfaceVariant,
    onSecondaryContainer = PhosphorGreen,
    tertiary = PhosphorGreenBright,
    onTertiary = GunmetalBackground,
    background = GunmetalBackground,
    onBackground = TextPhosphor,
    surface = GunmetalSurface,
    onSurface = TextPhosphor,
    surfaceVariant = GunmetalSurfaceVariant,
    onSurfaceVariant = TextPhosphorDim,
    outline = BorderPhosphor.copy(alpha = 0.5f),
    outlineVariant = BorderPhosphor.copy(alpha = 0.3f),
    error = Color(0xFFFF4444),
    onError = Color.White
)

// Light color scheme (fallback - still uses dark terminal aesthetic)
private val LightColorScheme = lightColorScheme(
    primary = PhosphorGreen,
    onPrimary = GunmetalBackground,
    primaryContainer = PhosphorGreenDim.copy(alpha = 0.3f),
    onPrimaryContainer = PhosphorGreenBright,
    secondary = PhosphorGreenDim,
    onSecondary = GunmetalBackground,
    secondaryContainer = GunmetalSurfaceVariant,
    onSecondaryContainer = PhosphorGreen,
    tertiary = PhosphorGreenBright,
    onTertiary = GunmetalBackground,
    background = GunmetalBackground,
    onBackground = TextPhosphor,
    surface = GunmetalSurface,
    onSurface = TextPhosphor,
    surfaceVariant = GunmetalSurfaceVariant,
    onSurfaceVariant = TextPhosphorDim,
    outline = BorderPhosphor.copy(alpha = 0.5f),
    outlineVariant = BorderPhosphor.copy(alpha = 0.3f),
    error = Color(0xFFFF4444),
    onError = Color.White
)

@Composable
fun GhostTheme(
    darkTheme: Boolean = true, // Force dark theme for terminal aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme // Always use dark theme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GhostTypography,
        content = content
    )
}

// Cyberpunk color accessors for non-Compose usage
object GhostColors {
    val phosphorGreen = PhosphorGreen
    val phosphorDim = PhosphorGreenDim
    val phosphorBright = PhosphorGreenBright
    val gunmetalBg = GunmetalBackground
    val gunmetalSurface = GunmetalSurface
    val gunmetalSurfaceVariant = GunmetalSurfaceVariant
    val textPhosphor = TextPhosphor
    val textPhosphorDim = TextPhosphorDim
    val borderPhosphor = BorderPhosphor
}
