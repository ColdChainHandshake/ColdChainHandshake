package com.coldchain.handshake.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = IceBlue,
    onPrimary = DeepNavy,
    primaryContainer = SurfaceLightDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentCyan,
    onSecondary = DeepNavy,
    background = DeepNavy,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceLightDark,
    onSurfaceVariant = TextSecondary,
    error = StatusRed,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryCold,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F5FE),
    onPrimaryContainer = Color(0xFF01579B),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    background = Color(0xFFF5F9FC),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF49454F),
    error = StatusRed,
    onError = Color.White
)

@Composable
fun ColdChainTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
