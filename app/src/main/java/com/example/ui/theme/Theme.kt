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

private fun getDarkColorScheme(themeColor: String) = darkColorScheme(
    primary = when (themeColor) {
        "SUNSET_ORANGE" -> OrangePrimary
        "ROYAL_PURPLE" -> PurplePrimary
        "FOREST_GREEN" -> GreenPrimary
        "OCEAN_BLUE" -> OceanPrimary
        else -> CyanPrimary
    },
    secondary = when (themeColor) {
        "SUNSET_ORANGE" -> OrangeSecondary
        "ROYAL_PURPLE" -> PurpleSecondary
        "FOREST_GREEN" -> GreenSecondary
        "OCEAN_BLUE" -> OceanSecondary
        else -> BlueSecondary
    },
    tertiary = when (themeColor) {
        "SUNSET_ORANGE" -> BronzeTertiary
        "ROYAL_PURPLE" -> VioletTertiary
        "FOREST_GREEN" -> MintTertiary
        "OCEAN_BLUE" -> CyanTertiary
        else -> IndigoTertiary
    },
    background = DarkSlateBg,
    surface = DarkSlateSurface,
    onPrimary = DarkSlateBg,
    onSecondary = TextWhite,
    onTertiary = TextWhite,
    onBackground = TextWhite,
    onSurface = TextWhite,
    surfaceVariant = DarkSlateSurface,
    onSurfaceVariant = TextGray
)

private fun getLightColorScheme(themeColor: String) = lightColorScheme(
    primary = when (themeColor) {
        "SUNSET_ORANGE" -> OrangePrimary
        "ROYAL_PURPLE" -> PurplePrimary
        "FOREST_GREEN" -> GreenPrimary
        "OCEAN_BLUE" -> OceanPrimary
        else -> BlueSecondary
    },
    secondary = when (themeColor) {
        "SUNSET_ORANGE" -> OrangeSecondary
        "ROYAL_PURPLE" -> PurpleSecondary
        "FOREST_GREEN" -> GreenSecondary
        "OCEAN_BLUE" -> OceanSecondary
        else -> IndigoTertiary
    },
    tertiary = when (themeColor) {
        "SUNSET_ORANGE" -> BronzeTertiary
        "ROYAL_PURPLE" -> VioletTertiary
        "FOREST_GREEN" -> MintTertiary
        "OCEAN_BLUE" -> CyanTertiary
        else -> CyanPrimary
    },
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    onPrimary = Color.White,
    onSecondary = DarkSlateBg,
    onTertiary = DarkSlateBg,
    onBackground = DarkSlateBg,
    onSurface = DarkSlateBg,
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569)
)

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
