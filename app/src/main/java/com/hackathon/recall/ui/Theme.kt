package com.hackathon.recall.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm paper background (matches the launcher icon and the "paperwork assistant" tagline); cards
// stay white so they read as pages sitting on that background. Matches themes.xml window_background,
// so there's no colour flash between the pre-Compose window and the first Compose frame.
private val LightPrimary = Color(0xFF254479) // Deep Blue
private val LightBackground = Color(0xFFFDF8F3)
private val LightSurface = Color(0xFFFFFFFF)
private val LightForeground = Color(0xFF2C2D35)

private val DarkPrimary = Color(0xFFEAEAEA) // Near White
private val DarkBackground = Color(0xFF212121)
private val DarkSurface = Color(0xFF303030)
private val DarkForeground = Color(0xFFFAFAFA)

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    background = LightBackground,
    surface = LightSurface,
    onPrimary = Color.White,
    onBackground = LightForeground,
    onSurface = LightForeground
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onBackground = DarkForeground,
    onSurface = DarkForeground
)

@Composable
fun RecallTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
