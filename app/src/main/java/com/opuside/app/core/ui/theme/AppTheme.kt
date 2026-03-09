package com.opuside.app.core.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════════════
// APP THEME SYSTEM — 6 профессиональных тем
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

    // ── OBSIDIAN — серо-чёрная, минималистичная ───────────────────────────────
    OBSIDIAN(ThemeColors(
        bg             = Color(0xFF0D0D0D),
        surface        = Color(0xFF161616),
        surfaceVariant = Color(0xFF1E1E1E),
        border         = Color(0xFF2A2A2A),
        text1          = Color(0xFFE2E2E2),
        text2          = Color(0xFF888888),
        text3          = Color(0xFF505050),
        accent         = Color(0xFF9E9E9E),
        green          = Color(0xFF6DBF67),
        red            = Color(0xFFCF6679),
        yellow         = Color(0xFFE8C46A),
        orange         = Color(0xFFD4956A),
        purple         = Color(0xFF9D7CD8),
        isDark         = true,
        displayName    = "Obsidian",
        emoji          = "⬛",
        description    = "Серо-чёрная, максимальный фокус"
    )),

    // ── MIDNIGHT — глубокий синий, текущий дефолт ─────────────────────────────
    MIDNIGHT(ThemeColors(
        bg             = Color(0xFF0A0E14),
        surface        = Color(0xFF12171E),
        surfaceVariant = Color(0xFF1A1F26),
        border         = Color(0xFF2D3339),
        text1          = Color(0xFFE8EDF3),
        text2          = Color(0xFF8B949E),
        text3          = Color(0xFF6E7681),
        accent         = Color(0xFF4A9EFF),
        green          = Color(0xFF4CAF50),
        red            = Color(0xFFE53935),
        yellow         = Color(0xFFFFC107),
        orange         = Color(0xFFFF9800),
        purple         = Color(0xFF9C27B0),
        isDark         = true,
        displayName    = "Midnight",
        emoji          = "🌙",
        description    = "Синяя ночь, классика"
    )),

    // ── SLATE — серо-синий, профессиональный enterprise ──────────────────────
    SLATE(ThemeColors(
        bg             = Color(0xFF0F1419),
        surface        = Color(0xFF16202A),
        surfaceVariant = Color(0xFF1C2A36),
        border         = Color(0xFF2D3F50),
        text1          = Color(0xFFCDD9E5),
        text2          = Color(0xFF768A9D),
        text3          = Color(0xFF546070),
        accent         = Color(0xFF58A6FF),
        green          = Color(0xFF3FB950),
        red            = Color(0xFFF85149),
        yellow         = Color(0xFFD29922),
        orange         = Color(0xFFDB6D28),
        purple         = Color(0xFFBC8CFF),
        isDark         = true,
        displayName    = "Slate",
        emoji          = "🪨",
        description    = "Серо-синий, GitHub стиль"
    )),

    // ── TERMINAL — зелёный на чёрном, хакерский ───────────────────────────────
    TERMINAL(ThemeColors(
        bg             = Color(0xFF080D08),
        surface        = Color(0xFF0D140D),
        surfaceVariant = Color(0xFF121A12),
        border         = Color(0xFF1A2E1A),
        text1          = Color(0xFF98C379),
        text2          = Color(0xFF5A8A5A),
        text3          = Color(0xFF3A5A3A),
        accent         = Color(0xFF4EC94E),
        green          = Color(0xFF4EC94E),
        red            = Color(0xFFE06C75),
        yellow         = Color(0xFFE5C07B),
        orange         = Color(0xFFD19A66),
        purple         = Color(0xFFC678DD),
        isDark         = true,
        displayName    = "Terminal",
        emoji          = "💻",
        description    = "Зелёный на чёрном, dev-режим"
    )),

    // ── DUSK — тёплая янтарная, уютная и самобытная ───────────────────────────
    DUSK(ThemeColors(
        bg             = Color(0xFF1A1208),
        surface        = Color(0xFF221A0A),
        surfaceVariant = Color(0xFF2C220E),
        border         = Color(0xFF3D2E14),
        text1          = Color(0xFFF5E6C8),
        text2          = Color(0xFFC4A96A),
        text3          = Color(0xFF8B7340),
        accent         = Color(0xFFD97706),
        green          = Color(0xFF86B35A),
        red            = Color(0xFFCF6679),
        yellow         = Color(0xFFF5C518),
        orange         = Color(0xFFE8890A),
        purple         = Color(0xFFB07FCC),
        isDark         = true,
        displayName    = "Dusk",
        emoji          = "🌆",
        description    = "Тёплый янтарь, мягко для глаз"
    )),

    // ── ARCTIC — чистый белый, дневной режим ──────────────────────────────────
    ARCTIC(ThemeColors(
        bg             = Color(0xFFF5F7FA),
        surface        = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F4F8),
        border         = Color(0xFFDDE3EA),
        text1          = Color(0xFF1A1F26),
        text2          = Color(0xFF586069),
        text3          = Color(0xFF8B949E),
        accent         = Color(0xFF0969DA),
        green          = Color(0xFF1A7F37),
        red            = Color(0xFFCF222E),
        yellow         = Color(0xFF9A6700),
        orange         = Color(0xFFBC4C00),
        purple         = Color(0xFF8250DF),
        isDark         = false,
        displayName    = "Arctic",
        emoji          = "🤍",
        description    = "Чистый белый, максимальная ясность"
    ))
}
