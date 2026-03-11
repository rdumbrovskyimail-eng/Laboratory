package com.opuside.app.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

data class ThemeColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val text1: Color,
    val text2: Color,
    val text3: Color,
    val accent: Color,
    val green: Color,
    val red: Color,
    val yellow: Color,
    val orange: Color,
    val purple: Color,
    val isDark: Boolean,
    val displayName: String,
    val emoji: String,
    val description: String
)

enum class AppTheme(val config: ThemeColors) {

    CLOUD(ThemeColors(
        bg             = Color(0xFFF5F4F1),
        surface        = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0EEEA),
        border         = Color(0xFFE0DDD8),
        text1          = Color(0xFF1C1B19),
        text2          = Color(0xFF5F5E5B),
        text3          = Color(0xFF9C9A96),
        accent         = Color(0xFF3C7BE0),
        green          = Color(0xFF2E7D42),
        red            = Color(0xFFC62828),
        yellow         = Color(0xFF8D6E00),
        orange         = Color(0xFFBF5000),
        purple         = Color(0xFF7B1FA2),
        isDark         = false,
        displayName    = "Cloud",
        emoji          = "☁️",
        description    = "Тёплый белый, дневной режим"
    )),

    GRAPHITE(ThemeColors(
        bg             = Color(0xFF16161E),
        surface        = Color(0xFF1E1E28),
        surfaceVariant = Color(0xFF252530),
        border         = Color(0xFF30303E),
        text1          = Color(0xFFCDD6F4),
        text2          = Color(0xFF7F849C),
        text3          = Color(0xFF505264),
        accent         = Color(0xFF7AA2F7),
        green          = Color(0xFF9ECE6A),
        red            = Color(0xFFF38BA8),
        yellow         = Color(0xFFE5C07B),
        orange         = Color(0xFFFFAB70),
        purple         = Color(0xFFCBA6F7),
        isDark         = true,
        displayName    = "Graphite",
        emoji          = "◼️",
        description    = "Глубокая ночь, максимум фокуса"
    )),

    PEARL(ThemeColors(
        bg             = Color(0xFFF7F8FA),
        surface        = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEEF0F4),
        border         = Color(0xFFD8DCE4),
        text1          = Color(0xFF171A1F),
        text2          = Color(0xFF5B6070),
        text3          = Color(0xFF9298A5),
        accent         = Color(0xFF0D9373),
        green          = Color(0xFF1A7F37),
        red            = Color(0xFFCF222E),
        yellow         = Color(0xFF7D5E00),
        orange         = Color(0xFFB35900),
        purple         = Color(0xFF6E40C9),
        isDark         = false,
        displayName    = "Pearl",
        emoji          = "✨",
        description    = "Прохладный белый, AI-стиль"
    )),

    SLATE(ThemeColors(
        bg             = Color(0xFF141820),
        surface        = Color(0xFF1B2028),
        surfaceVariant = Color(0xFF222830),
        border         = Color(0xFF2F3640),
        text1          = Color(0xFFD0D7E0),
        text2          = Color(0xFF7A8594),
        text3          = Color(0xFF515C6B),
        accent         = Color(0xFF5B9EF0),
        green          = Color(0xFF4CAA5A),
        red            = Color(0xFFE05252),
        yellow         = Color(0xFFD4A24C),
        orange         = Color(0xFFD07840),
        purple         = Color(0xFFA882DE),
        isDark         = true,
        displayName    = "Slate",
        emoji          = "🌊",
        description    = "Серо-синий, GitHub стиль"
    )),

    MOCHA(ThemeColors(
        bg             = Color(0xFF171210),
        surface        = Color(0xFF1F1916),
        surfaceVariant = Color(0xFF28201C),
        border         = Color(0xFF3A302A),
        text1          = Color(0xFFE8DED4),
        text2          = Color(0xFFA89888),
        text3          = Color(0xFF6E6058),
        accent         = Color(0xFFC4956A),
        green          = Color(0xFF8AAE6E),
        red            = Color(0xFFC46B6B),
        yellow         = Color(0xFFD4B06A),
        orange         = Color(0xFFD4906A),
        purple         = Color(0xFFA080B8),
        isDark         = true,
        displayName    = "Mocha",
        emoji          = "☕",
        description    = "Тёплый espresso, мягко для глаз"
    )),

    TEAL(ThemeColors(
        bg             = Color(0xFF0E1618),
        surface        = Color(0xFF141E21),
        surfaceVariant = Color(0xFF1A2629),
        border         = Color(0xFF263336),
        text1          = Color(0xFFD0DDE0),
        text2          = Color(0xFF7A9196),
        text3          = Color(0xFF506468),
        accent         = Color(0xFF4CA6A0),
        green          = Color(0xFF5AAE7A),
        red            = Color(0xFFD06868),
        yellow         = Color(0xFFCCAA55),
        orange         = Color(0xFFCC8855),
        purple         = Color(0xFF8E88C8),
        isDark         = true,
        displayName    = "Teal",
        emoji          = "🌿",
        description    = "Сине-зелёный, тренд 2026"
    ));
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
