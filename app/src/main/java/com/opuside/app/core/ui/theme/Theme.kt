package com.opuside.app.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ═══════════════════════════════════════════════════════════════════════════════
// COLORS
// ═══════════════════════════════════════════════════════════════════════════════

// Primary - Amber/Orange (Claude inspired)
private val Primary = Color(0xFFD97706)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFFFEF3C7)
private val OnPrimaryContainer = Color(0xFF78350F)

// Secondary - Purple
private val Secondary = Color(0xFF7C3AED)
private val OnSecondary = Color(0xFFFFFFFF)
private val SecondaryContainer = Color(0xFFEDE9FE)
private val OnSecondaryContainer = Color(0xFF4C1D95)

// Tertiary - Teal
private val Tertiary = Color(0xFF14B8A6)
private val OnTertiary = Color(0xFFFFFFFF)
private val TertiaryContainer = Color(0xFFCCFBF1)
private val OnTertiaryContainer = Color(0xFF134E4A)

// Error
private val Error = Color(0xFFEF4444)
private val OnError = Color(0xFFFFFFFF)
private val ErrorContainer = Color(0xFFFEE2E2)
private val OnErrorContainer = Color(0xFF7F1D1D)

// Neutral
private val Background = Color(0xFFFAFAFA)
private val OnBackground = Color(0xFF1F2937)
private val Surface = Color(0xFFFFFFFF)
private val OnSurface = Color(0xFF1F2937)
private val SurfaceVariant = Color(0xFFF3F4F6)
private val OnSurfaceVariant = Color(0xFF6B7280)
private val Outline = Color(0xFFD1D5DB)
private val OutlineVariant = Color(0xFFE5E7EB)

// Dark theme colors
private val BackgroundDark = Color(0xFF111827)
private val OnBackgroundDark = Color(0xFFF9FAFB)
private val SurfaceDark = Color(0xFF1F2937)
private val OnSurfaceDark = Color(0xFFF9FAFB)
private val SurfaceVariantDark = Color(0xFF374151)
private val OnSurfaceVariantDark = Color(0xFF9CA3AF)
private val OutlineDark = Color(0xFF4B5563)
private val OutlineVariantDark = Color(0xFF374151)

// ═══════════════════════════════════════════════════════════════════════════════
// COLOR SCHEMES
// ═══════════════════════════════════════════════════════════════════════════════

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = PrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = Color(0xFF4C1D95),
    onSecondaryContainer = SecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = TertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = ErrorContainer,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

// ═══════════════════════════════════════════════════════════════════════════════
// THEME COMPOSABLE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun OpusIDETheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color доступен на Android 12+, но мы на 16, так что работает
    dynamicColor: Boolean = false, // Отключаем чтобы использовать наши цвета
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) 
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
