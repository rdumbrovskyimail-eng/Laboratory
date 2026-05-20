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
    val isDark: Boolean,
    val purple: Color = Color(0xFFA855F7),
    val green: Color = Color(0xFF22C55E),
    val text1: Color = Color(0xFF111827),
    val text2: Color = Color(0xFF4B5563),
    val surfaceVariant: Color = Color(0xFFF3F4F6),
    val border: Color = Color(0xFFE5E7EB),
    val red: Color = Color(0xFFEF4444)
)

enum class AppTheme(val config: MinimalistColors) {
    LIGHT(MinimalistColors(Color(0xFFFFFFFF), Color(0xFFF9FAFB), Color(0xFF111827), Color(0xFF111827), false)),
    DARK(MinimalistColors(Color(0xFFFFFFFF), Color(0xFFF9FAFB), Color(0xFF111827), Color(0xFF111827), false)),
    GRAPHITE(MinimalistColors(Color(0xFFFFFFFF), Color(0xFFF9FAFB), Color(0xFF111827), Color(0xFF111827), false)),
    CLOUD(MinimalistColors(Color(0xFFFFFFFF), Color(0xFFF9FAFB), Color(0xFF111827), Color(0xFF111827), false))
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