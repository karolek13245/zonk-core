package com.example.aiapp

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// iOS-style palette (system font stays Roboto on Android; SF Pro is Apple-licensed).
private val iosBlue = Color(0xFF007AFF)
private val iosBlueDark = Color(0xFF0A84FF)
private val iosGreen = Color(0xFF34C759)
private val iosBgLight = Color(0xFFF2F2F7)
private val iosBgDark = Color(0xFF1C1C1E)
private val iosCardLight = Color(0xFFFFFFFF)
private val iosCardDark = Color(0xFF2C2C2E)
private val iosSepLight = Color(0xFFE5E5EA)
private val iosSepDark = Color(0xFF3A3A3C)

private val LightColors = lightColorScheme(
    primary = iosBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = iosBlue,
    background = iosBgLight,
    onBackground = Color(0xFF1C1C1E),
    surface = iosBgLight,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = iosCardLight,
    onSurfaceVariant = Color(0xFF1C1C1E),
    outlineVariant = iosSepLight,
)

private val DarkColors = darkColorScheme(
    primary = iosBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C2A3A),
    onPrimaryContainer = iosBlueDark,
    background = iosBgDark,
    onBackground = Color(0xFFE5E5EA),
    surface = iosBgDark,
    onSurface = Color(0xFFE5E5EA),
    surfaceVariant = iosCardDark,
    onSurfaceVariant = Color(0xFFE5E5EA),
    outlineVariant = iosSepDark,
)

@Composable
fun AIAppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
