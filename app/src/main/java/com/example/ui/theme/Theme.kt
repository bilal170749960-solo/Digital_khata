package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.data.model.ThemeMode

private val DarkColorScheme = darkColorScheme(
    primary = KhataGreenMint,
    onPrimary = Color(0xFF02361E),
    primaryContainer = Color(0xFF0A4F30),
    onPrimaryContainer = Color(0xFFD4F4E2),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF003831),
    secondaryContainer = Color(0xFF134E48),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF78350F),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = KhataSurfaceDark,
    onBackground = KhataTextPrimaryDark,
    surface = KhataSurfaceCardDark,
    onSurface = KhataTextPrimaryDark,
    surfaceVariant = KhataSurfaceElevatedDark,
    onSurfaceVariant = KhataTextSecondaryDark,
    outline = KhataBorderDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)

private val LightColorScheme = lightColorScheme(
    primary = KhataGreenPrimary,
    onPrimary = Color.White,
    primaryContainer = KhataGreenContainer,
    onPrimaryContainer = KhataOnGreenContainer,
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),
    tertiary = KhataAmber,
    onTertiary = Color.White,
    tertiaryContainer = KhataAmberContainer,
    onTertiaryContainer = KhataOnAmberContainer,
    background = KhataSurfaceLight,
    onBackground = KhataTextPrimaryLight,
    surface = KhataSurfaceCardLight,
    onSurface = KhataTextPrimaryLight,
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = KhataTextSecondaryLight,
    outline = KhataBorderLight,
    error = KhataRed,
    onError = Color.White,
    errorContainer = KhataRedContainer,
    onErrorContainer = KhataOnRedContainer
)

@Composable
fun DigitalKhataTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
