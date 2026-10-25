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
private val iosRedLight = Color(0xFFD70015)
private val iosRedDark = Color(0xFFFF6B63)

// Every colour role is set on purpose. Roles left out would fall back to Material's own purple-grey
// defaults, and those showed up as off-colour buttons, switches, dialogs and menus next to the iOS blue and grey.
private val LightColors = lightColorScheme(
    primary = iosBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F0FF),
    onPrimaryContainer = iosBlue,
    secondary = iosBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5F0FF),
    onSecondaryContainer = Color(0xFF0062CC),
    error = iosRedLight,
    onError = Color.White,
    background = iosBgLight,
    onBackground = Color(0xFF1C1C1E),
    surface = iosBgLight,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = iosCardLight,
    onSurfaceVariant = Color(0xFF1C1C1E),
    outline = Color(0xFFAEAEB2),
    outlineVariant = iosSepLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFE9E9EE),
)

private val DarkColors = darkColorScheme(
    primary = iosBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C2A3A),
    onPrimaryContainer = iosBlueDark,
    secondary = iosBlueDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1C2A3A),
    onSecondaryContainer = Color(0xFF64B5FF),
    error = iosRedDark,
    onError = Color(0xFF1C1C1E),
    background = iosBgDark,
    onBackground = Color(0xFFE5E5EA),
    surface = iosBgDark,
    onSurface = Color(0xFFE5E5EA),
    surfaceVariant = iosCardDark,
    onSurfaceVariant = Color(0xFFE5E5EA),
    outline = Color(0xFF636366),
    outlineVariant = iosSepDark,
    surfaceContainerLowest = Color(0xFF151517),
    surfaceContainerLow = Color(0xFF232325),
    surfaceContainer = Color(0xFF3A3A3C),
    surfaceContainerHigh = iosCardDark,
    surfaceContainerHighest = Color(0xFF3A3A3C),
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
