package com.mystt.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0E7C66)
private val TealLight = Color(0xFF13A085)
private val TealDark = Color(0xFF0A5D4C)

private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    secondary = TealLight, background = Color(0xFFF3FAF8), surface = Color.White
)
private val DarkColors = darkColorScheme(
    primary = TealLight, onPrimary = Color.White,
    secondary = Teal, background = Color(0xFF0D1411), surface = Color(0xFF141C19)
)

@Composable
fun MySttTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
