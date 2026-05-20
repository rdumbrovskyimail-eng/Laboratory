package com.opuside.app.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════════
// APP THEME SYSTEM — 6 профессиональных тем (2026 design trends)
// ═══════════════════════════════════════════════════════════════════════════════

data class MinimalistColors(
    val bg: Color,
    val surface: Color,
    val text: Color,
    val accent: Color,
    val isDark: Boolean
)

enum class AppTheme(val config: MinimalistColors) {
    LIGHT(MinimalistColors(Color(0xFFFFFFFF), Color(0xFFF5F5F5), Color(0xFF000000), Color(0xFF000000), false)),
    DARK(MinimalistColors(Color(0xFF000000), Color(0xFF121212), Color(0xFFFFFFFF), Color(0xFFFFFFFF), true))
}

fun AppTheme.toColorScheme() = if (config.isDark) {
    darkColorScheme(
        primary            = config.accent,
        onPrimary          = config.bg,
        secondary          = config.purple,
        onSecondary        = config.bg,
        tertiary           = config.green,
        onTertiary         = config.bg,
        background         = config.bg,
        onBackground       = config.text1,
        surface            = config.surface,
        onSurface          = config.text1,
        surfaceVariant     = config.surfaceVariant,
        onSurfaceVariant   = config.text2,
        outline            = config.border,
        outlineVariant     = config.border,
        error              = config.red,
        onError            = config.bg,
        inverseSurface     = config.text1,
        inverseOnSurface   = config.bg,
        inversePrimary     = config.accent
    )
} else {
    lightColorScheme(
        primary            = config.accent,
        onPrimary          = Color.White,
        secondary          = config.purple,
        onSecondary        = Color.White,
        tertiary           = config.green,
        onTertiary         = Color.White,
        background         = config.bg,
        onBackground       = config.text1,
        surface            = config.surface,
        onSurface          = config.text1,
        surfaceVariant     = config.surfaceVariant,
        onSurfaceVariant   = config.text2,
        outline            = config.border,
        outlineVariant     = config.border,
        error              = config.red,
        onError            = Color.White,
        inverseSurface     = config.text1,
        inverseOnSurface   = config.bg,
        inversePrimary     = config.accent
    )
}