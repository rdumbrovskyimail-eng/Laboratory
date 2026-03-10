package com.opuside.app.core.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════════
// APP THEME SYSTEM — 6 профессиональных тем (2026 design trends)
// ═══════════════════════════════════════════════════════════════════════════════

data class ThemeColors(
    // Фоны
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    // Текст
    val text1: Color,
    val text2: Color,
    val text3: Color,
    // Акценты
    val accent: Color,
    val green: Color,
    val red: Color,
    val yellow: Color,
    val orange: Color,
    val purple: Color,
    // Мета
    val isDark: Boolean,
    val displayName: String,
    val emoji: String,
    val description: String
)

enum class AppTheme(val config: ThemeColors) {

    // ══════════════════════════════════════════════════════════════════════════
    // ОСНОВНЫЕ (2 штуки)
    // ══════════════════════════════════════════════════════════════════════════

    // ── CLOUD — основная белая, Pantone Cloud Dancer 2026 + Google Material ──
    // Вдохновлена: Cloud Dancer #F0EEE9, Google Material You, Apple HIG
    // Тёплый off-white, не стерильный, мягкий для глаз весь рабочий день
    CLOUD(ThemeColors(
        bg             = Color(0xFFF5F4F1),   // тёплый off-white (Cloud Dancer адаптация)
        surface        = Color(0xFFFFFFFF),   // чистый белый для карточек
        surfaceVariant = Color(0xFFF0EEEA),   // Cloud Dancer #F0EEE9
        border         = Color(0xFFE0DDD8),   // мягкий тёплый серый
        text1          = Color(0xFF1C1B19),   // почти чёрный, тёплый
        text2          = Color(0xFF5F5E5B),   // средний серый
        text3          = Color(0xFF9C9A96),   // приглушённый
        accent         = Color(0xFF3C7BE0),   // спокойный синий (Google-style)
        green          = Color(0xFF2E7D42),   // тёмный зелёный, читаемый на белом
        red            = Color(0xFFC62828),   // тёмный красный
        yellow         = Color(0xFF8D6E00),   // тёмный янтарь (WCAG на белом)
        orange         = Color(0xFFBF5000),   // тёмный оранж
        purple         = Color(0xFF7B1FA2),   // глубокий фиолетовый
        isDark         = false,
        displayName    = "Cloud",
        emoji          = "☁️",
        description    = "Тёплый белый, дневной режим"
    )),

    // ── GRAPHITE — основная тёмно-серая, чистый нейтральный серый ─────────────
    // Вдохновлена: Spacegray, One Monokai, "mood mode dark" 2026
    // Нейтральный серый без синевы/зелени/теплоты — чистый графит
    GRAPHITE(ThemeColors(
        bg             = Color(0xFF1A1A1A),   // тёмный графит
        surface        = Color(0xFF222222),   // поверхность
        surfaceVariant = Color(0xFF2A2A2A),   // вариант
        border         = Color(0xFF363636),   // граница
        text1          = Color(0xFFE4E4E4),   // основной текст
        text2          = Color(0xFF8C8C8C),   // вторичный
        text3          = Color(0xFF5C5C5C),   // третичный
        accent         = Color(0xFF7B9FCC),   // приглушённый стальной синий
        green          = Color(0xFF7AAE7A),   // спокойный зелёный
        red            = Color(0xFFCC6666),   // мягкий красный
        yellow         = Color(0xFFCCB366),   // приглушённый жёлтый
        orange         = Color(0xFFCC8E66),   // тёплый оранж
        purple         = Color(0xFF9E85B8),   // лавандовый
        isDark         = true,
        displayName    = "Graphite",
        emoji          = "◼️",
        description    = "Нейтральный серый, классика"
    )),

    // ══════════════════════════════════════════════════════════════════════════
    // ДОПОЛНИТЕЛЬНЫЕ (4 штуки)
    // ══════════════════════════════════════════════════════════════════════════

    // ── OBSIDIAN — глубокий чёрный, AMOLED-friendly ──────────────────────────
    // Вдохновлена: One Dark, чистый чёрный для OLED-экранов
    // Максимальный контраст, экономия батареи, полный фокус
    OBSIDIAN(ThemeColors(
        bg             = Color(0xFF0A0A0A),   // почти чёрный
        surface        = Color(0xFF131313),   // тёмная поверхность
        surfaceVariant = Color(0xFF1B1B1B),   // вариант
        border         = Color(0xFF282828),   // тонкая граница
        text1          = Color(0xFFDCDCDC),   // мягкий белый (не 100%)
        text2          = Color(0xFF787878),   // средний серый
        text3          = Color(0xFF484848),   // приглушённый
        accent         = Color(0xFFA0A0A0),   // серый акцент — полный монохром
        green          = Color(0xFF6BA36B),   // десатурированный зелёный
        red            = Color(0xFFB86B6B),   // десатурированный красный
        yellow         = Color(0xFFB8A86B),   // десатурированный жёлтый
        orange         = Color(0xFFB8906B),   // десатурированный оранж
        purple         = Color(0xFF8F7AAE),   // десатурированный фиолетовый
        isDark         = true,
        displayName    = "Obsidian",
        emoji          = "⬛",
        description    = "Чёрный AMOLED, максимальный фокус"
    )),

    // ── SLATE — серо-синий, GitHub / VS Code стиль ───────────────────────────
    // Вдохновлена: GitHub Dark, Ayu Mirage, Night Owl
    // Профессиональный enterprise-уровень с холодным оттенком
    SLATE(ThemeColors(
        bg             = Color(0xFF141820),   // тёмный с лёгкой синевой
        surface        = Color(0xFF1B2028),   // поверхность
        surfaceVariant = Color(0xFF222830),   // вариант
        border         = Color(0xFF2F3640),   // граница
        text1          = Color(0xFFD0D7E0),   // холодный белый
        text2          = Color(0xFF7A8594),   // серо-синий
        text3          = Color(0xFF515C6B),   // приглушённый
        accent         = Color(0xFF5B9EF0),   // GitHub blue
        green          = Color(0xFF4CAA5A),   // зелёный коммитов
        red            = Color(0xFFE05252),   // красный ошибок
        yellow         = Color(0xFFD4A24C),   // предупреждения
        orange         = Color(0xFFD07840),   // оранж
        purple         = Color(0xFFA882DE),   // фиолетовый
        isDark         = true,
        displayName    = "Slate",
        emoji          = "🌊",
        description    = "Серо-синий, GitHub стиль"
    )),

    // ── MOCHA — тёплый espresso, тренд Mocha Mousse 2025–2026 ───────────────
    // Вдохновлена: Pantone Mocha Mousse, Benjamin Moore Silhouette 2026
    // Deep brown как "новый чёрный" — тёплый контраст без жёсткости
    MOCHA(ThemeColors(
        bg             = Color(0xFF171210),   // тёмный espresso
        surface        = Color(0xFF1F1916),   // поверхность с теплотой
        surfaceVariant = Color(0xFF28201C),   // вариант
        border         = Color(0xFF3A302A),   // тёплая граница
        text1          = Color(0xFFE8DED4),   // кремовый белый
        text2          = Color(0xFFA89888),   // тёплый серый
        text3          = Color(0xFF6E6058),   // приглушённый
        accent         = Color(0xFFC4956A),   // медный/amber акцент
        green          = Color(0xFF8AAE6E),   // оливковый зелёный
        red            = Color(0xFFC46B6B),   // тёплый красный
        yellow         = Color(0xFFD4B06A),   // золотистый
        orange         = Color(0xFFD4906A),   // терракота
        purple         = Color(0xFFA080B8),   // приглушённый лиловый
        isDark         = true,
        displayName    = "Mocha",
        emoji          = "☕",
        description    = "Тёплый espresso, мягко для глаз"
    )),

    // ── TEAL — Transformative Teal, WGSN Color of 2026 ──────────────────────
    // Вдохновлена: WGSN Transformative Teal #23545B, Behr Jade
    // Глубокий сине-зелёный — единство природы и технологий
    TEAL(ThemeColors(
        bg             = Color(0xFF0E1618),   // очень тёмный teal
        surface        = Color(0xFF141E21),   // поверхность
        surfaceVariant = Color(0xFF1A2629),   // вариант
        border         = Color(0xFF263336),   // граница с teal оттенком
        text1          = Color(0xFFD0DDE0),   // холодный светлый
        text2          = Color(0xFF7A9196),   // серо-teal
        text3          = Color(0xFF506468),   // приглушённый
        accent         = Color(0xFF4CA6A0),   // Transformative Teal (осветлённый для UI)
        green          = Color(0xFF5AAE7A),   // мятный зелёный
        red            = Color(0xFFD06868),   // коралловый красный
        yellow         = Color(0xFFCCAA55),   // тёплый жёлтый
        orange         = Color(0xFFCC8855),   // янтарный
        purple         = Color(0xFF8E88C8),   // лавандовый
        isDark         = true,
        displayName    = "Teal",
        emoji          = "🌿",
        description    = "Сине-зелёный, тренд 2026"
    ))
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