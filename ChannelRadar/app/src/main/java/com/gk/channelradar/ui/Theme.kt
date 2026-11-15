package com.gk.channelradar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5A5A),
    onPrimary = Color.White,
    secondary = Color(0xFFFFB74D),
    tertiary = Color(0xFF5BE0A0),
    background = Color(0xFF0F1115),
    surface = Color(0xFF15181E),
    surfaceVariant = Color(0xFF20252D),
    surfaceContainer = Color(0xFF1A1E25),
    surfaceContainerHigh = Color(0xFF20252D),
    onSurfaceVariant = Color(0xFFA8B0BC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFD32F2F),
    secondary = Color(0xFFE65100),
    tertiary = Color(0xFF1B7F4B),
)

val GoodGreen = Color(0xFF3DBE7E)
val WarnAmber = Color(0xFFF2A93B)

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

fun scoreColor(score: Double): Color = when {
    score >= 70 -> Color(0xFF2FBF71)
    score >= 50 -> Color(0xFFF2A93B)
    else -> Color(0xFF8A94A3)
}
