package com.gk.viralradar.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Hot = Color(0xFFFF3D57)
val Gold = Color(0xFFFFB020)
val Mint = Color(0xFF22C55E)
val Sky = Color(0xFF3B82F6)
val Muted = Color(0xFF9CA3AF)

fun scoreColor(s: Int) = when {
    s >= 75 -> Hot
    s >= 58 -> Gold
    s >= 40 -> Sky
    else -> Muted
}

@Composable
fun ViralTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Hot, secondary = Gold, background = Color(0xFF0F0F14), surface = Color(0xFF17171F),
        surfaceVariant = Color(0xFF23232E), onBackground = Color(0xFFF2F2F5), onSurface = Color(0xFFF2F2F5),
    ) else lightColorScheme(
        primary = Color(0xFFE11D48), secondary = Color(0xFFD97706), background = Color(0xFFF7F7FA),
        surface = Color.White, surfaceVariant = Color(0xFFEDEDF2),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
