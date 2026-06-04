package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

import androidx.compose.material3.ColorScheme

private fun getDarkColorScheme(themeColor: String): ColorScheme {
    val primaryColor = when (themeColor) {
        "SUNSET_ORANGE" -> OrangePrimary
        "ROYAL_PURPLE" -> PurplePrimary
        "FOREST_GREEN" -> GreenPrimary
        "OCEAN_BLUE" -> OceanPrimary
        else -> CyanPrimary
    }
    val secondaryColor = when (themeColor) {
        "SUNSET_ORANGE" -> OrangeSecondary
        "ROYAL_PURPLE" -> PurpleSecondary
        "FOREST_GREEN" -> GreenSecondary
        "OCEAN_BLUE" -> OceanSecondary
        else -> BlueSecondary
    }
    val tertiaryColor = when (themeColor) {
        "SUNSET_ORANGE" -> BronzeTertiary
        "ROYAL_PURPLE" -> VioletTertiary
        "FOREST_GREEN" -> MintTertiary
        "OCEAN_BLUE" -> CyanTertiary
        else -> IndigoTertiary
    }

    // Beautifully customized background, surface, and surfaceContainer per theme color
    val (bg, surf, surfContainer) = when (themeColor) {
        "SUNSET_ORANGE" -> Triple(Color(0xFF0F0B0A), Color(0xFF1E1310), Color(0xFF2B1A16))
        "ROYAL_PURPLE" -> Triple(Color(0xFF0F0A15), Color(0xFF1A1324), Color(0xFF261D33))
        "FOREST_GREEN" -> Triple(Color(0xFF080D0C), Color(0xFF101B17), Color(0xFF182A24))
        "OCEAN_BLUE" -> Triple(Color(0xFF0A0F1D), Color(0xFF141F35), Color(0xFF1A2A44))
        else -> Triple(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF334155)) // COSMIC_CYAN / Default Slate
    }

    val primContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFF431407)
        "ROYAL_PURPLE" -> Color(0xFF3B0764)
        "FOREST_GREEN" -> Color(0xFF022C22)
        "OCEAN_BLUE" -> Color(0xFF172554)
        else -> Color(0xFF083344)
    }

    val onPrimContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFFFED7AA)
        "ROYAL_PURPLE" -> Color(0xFFE9D5FF)
        "FOREST_GREEN" -> Color(0xFFA7F3D0)
        "OCEAN_BLUE" -> Color(0xFFBFDBFE)
        else -> Color(0xFF7DD3FC)
    }

    val secContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFF3B0F03)
        "ROYAL_PURPLE" -> Color(0xFF22084E)
        "FOREST_GREEN" -> Color(0xFF022C22)
        "OCEAN_BLUE" -> Color(0xFF172554)
        else -> Color(0xFF0C4A6E)
    }

    val onSecContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFFFFE4D6)
        "ROYAL_PURPLE" -> Color(0xFFF3E8FF)
        "FOREST_GREEN" -> Color(0xFFD1FAE5)
        "OCEAN_BLUE" -> Color(0xFFDBEAFE)
        else -> Color(0xFFE0F2FE)
    }

    val tertContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFF451A03)
        "ROYAL_PURPLE" -> Color(0xFF4C0519)
        "FOREST_GREEN" -> Color(0xFF064E3B)
        "OCEAN_BLUE" -> Color(0xFF164E63)
        else -> Color(0xFF1E1B4B)
    }

    val onTertContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFFFEF3C7)
        "ROYAL_PURPLE" -> Color(0xFFFCE7F3)
        "FOREST_GREEN" -> Color(0xFFA7F3D0)
        "OCEAN_BLUE" -> Color(0xFFCFFAFE)
        else -> Color(0xFFE0E7FF)
    }

    return darkColorScheme(
        primary = primaryColor,
        onPrimary = Color(0xFF0A0F1D),
        primaryContainer = primContainer,
        onPrimaryContainer = onPrimContainer,
        secondary = secondaryColor,
        onSecondary = Color.White,
        secondaryContainer = secContainer,
        onSecondaryContainer = onSecContainer,
        tertiary = tertiaryColor,
        onTertiary = Color(0xFF0A0F1D),
        tertiaryContainer = tertContainer,
        onTertiaryContainer = onTertContainer,
        background = bg,
        onBackground = Color(0xFFF8FAFC),
        surface = surf,
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = surf,
        onSurfaceVariant = Color(0xFF94A3B8),
        inversePrimary = primaryColor,
        error = Color(0xFFF87171),
        onError = Color(0xFF7F1D1D),
        errorContainer = Color(0xFF991B1B),
        onErrorContainer = Color(0xFFFECACA),
        outline = Color(0xFF475569),
        outlineVariant = Color(0xFF334155),
        surfaceContainer = surfContainer,
        surfaceContainerLow = bg,
        surfaceContainerHigh = surfContainer,
        surfaceContainerLowest = Color(0xFF05050A),
        surfaceContainerHighest = surfContainer
    )
}

private fun getLightColorScheme(themeColor: String): ColorScheme {
    val primaryColor = when (themeColor) {
        "SUNSET_ORANGE" -> OrangeSecondary
        "ROYAL_PURPLE" -> Color(0xFF7C3AED)
        "FOREST_GREEN" -> GreenSecondary
        "OCEAN_BLUE" -> OceanSecondary
        else -> Color(0xFF0284C7)
    }
    val secondaryColor = when (themeColor) {
        "SUNSET_ORANGE" -> OrangeSecondary
        "ROYAL_PURPLE" -> PurpleSecondary
        "FOREST_GREEN" -> GreenSecondary
        "OCEAN_BLUE" -> OceanSecondary
        else -> BlueSecondary
    }
    val tertiaryColor = when (themeColor) {
        "SUNSET_ORANGE" -> OrangePrimary
        "ROYAL_PURPLE" -> Color(0xFF9333EA)
        "FOREST_GREEN" -> Color(0xFF10B981)
        "OCEAN_BLUE" -> Color(0xFF06B6D4)
        else -> Color(0xFF6366F1)
    }

    // Beautifully customized background, surface, and surfaceContainer per theme color
    val (bg, surf, surfContainer) = when (themeColor) {
        "SUNSET_ORANGE" -> Triple(Color(0xFFFFFDFB), Color(0xFFFFFFFF), Color(0xFFFFF2E6))
        "ROYAL_PURPLE" -> Triple(Color(0xFFFDFBFF), Color(0xFFFFFFFF), Color(0xFFF5EFFF))
        "FOREST_GREEN" -> Triple(Color(0xFFFBFDFB), Color(0xFFFFFFFF), Color(0xFFECF9F1))
        "OCEAN_BLUE" -> Triple(Color(0xFFF8FAFD), Color(0xFFFFFFFF), Color(0xFFEEF4FC))
        else -> Triple(Color(0xFFF8FAFC), Color(0xFFFFFFFF), Color(0xFFF1F5F9)) // COSMIC_CYAN / Default Slate
    }

    val primContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFFFFEDD5)
        "ROYAL_PURPLE" -> Color(0xFFF3E8FF)
        "FOREST_GREEN" -> Color(0xFFD1FAE5)
        "OCEAN_BLUE" -> Color(0xFFDBEAFE)
        else -> Color(0xFFE0F2FE)
    }

    val onPrimContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFF9A3412)
        "ROYAL_PURPLE" -> Color(0xFF5B21B6)
        "FOREST_GREEN" -> Color(0xFF065F46)
        "OCEAN_BLUE" -> Color(0xFF1E40AF)
        else -> Color(0xFF0369A1)
    }

    val secContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFFFFF7ED)
        "ROYAL_PURPLE" -> Color(0xFFFAF5FF)
        "FOREST_GREEN" -> Color(0xFFF0FDF4)
        "OCEAN_BLUE" -> Color(0xFFEFF6FF)
        else -> Color(0xFFF0F9FF)
    }

    val onSecContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFF854D0E)
        "ROYAL_PURPLE" -> Color(0xFF5B21B6)
        "FOREST_GREEN" -> Color(0xFF166534)
        "OCEAN_BLUE" -> Color(0xFF1E3A8A)
        else -> Color(0xFF075985)
    }

    val tertContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFFFEF3C7)
        "ROYAL_PURPLE" -> Color(0xFFFCE7F3)
        "FOREST_GREEN" -> Color(0xFFCCFBF1)
        "OCEAN_BLUE" -> Color(0xFFECFEFF)
        else -> Color(0xFFEEF2FF)
    }

    val onTertContainer = when (themeColor) {
        "SUNSET_ORANGE" -> Color(0xFF78350F)
        "ROYAL_PURPLE" -> Color(0xFF9D174D)
        "FOREST_GREEN" -> Color(0xFF115E59)
        "OCEAN_BLUE" -> Color(0xFF155E75)
        else -> Color(0xFF3730A3)
    }

    return lightColorScheme(
        primary = primaryColor,
        onPrimary = Color.White,
        primaryContainer = primContainer,
        onPrimaryContainer = onPrimContainer,
        secondary = secondaryColor,
        onSecondary = Color.White,
        secondaryContainer = secContainer,
        onSecondaryContainer = onSecContainer,
        tertiary = tertiaryColor,
        onTertiary = Color.White,
        tertiaryContainer = tertContainer,
        onTertiaryContainer = onTertContainer,
        background = bg,
        onBackground = Color(0xFF0F172A),
        surface = surf,
        onSurface = Color(0xFF0F172A),
        surfaceVariant = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF475569),
        inversePrimary = primaryColor,
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
        outline = Color(0xFF94A3B8),
        outlineVariant = Color(0xFFCBD5E1),
        surfaceContainer = surfContainer,
        surfaceContainerLow = bg,
        surfaceContainerHigh = surfContainer,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerHighest = surfContainer
    )
}

@Composable
fun MyApplicationTheme(
    themeMode: String = "SYSTEM",
    themeColor: String = "COSMIC_CYAN",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val useDark = when (themeMode) {
        "ALWAYS_LIGHT" -> false
        "ALWAYS_DARK" -> true
        else -> darkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDark -> getDarkColorScheme(themeColor)
        else -> getLightColorScheme(themeColor)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
