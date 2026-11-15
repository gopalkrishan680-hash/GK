package com.kahani.studio3d.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object K {
    val Bg = Color(0xFF0E0B1A)
    val Surface = Color(0xFF1A1530)
    val Surface2 = Color(0xFF241D42)
    val Stroke = Color(0xFF332A5C)
    val Violet = Color(0xFF7C4DFF)
    val Pink = Color(0xFFFF4F9A)
    val Cyan = Color(0xFF3DD6FF)
    val Yellow = Color(0xFFFFD84D)
    val Green = Color(0xFF3DDC97)
    val TextDim = Color(0xFFA79FC9)
    val Grad = Brush.linearGradient(listOf(Violet, Pink))
    val GradSoft = Brush.linearGradient(listOf(Color(0xFF2B1F5C), Color(0xFF4A1F4F)))
}

@Composable
fun KahaniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.Violet,
            onPrimary = Color.White,
            secondary = K.Pink,
            background = K.Bg,
            surface = K.Surface,
            surfaceVariant = K.Surface2,
            onSurface = Color.White,
            onSurfaceVariant = K.TextDim,
            outline = K.Stroke,
            surfaceContainer = K.Surface,
            surfaceContainerHigh = K.Surface2,
            surfaceContainerHighest = K.Surface2,
        ),
        content = content
    )
}

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) K.Grad else Brush.linearGradient(listOf(K.Stroke, K.Stroke)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
fun SoftButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = K.Surface2, border = BorderStroke(1.dp, K.Stroke)
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, tint = K.Cyan, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SectionCard(title: String? = null, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(K.Surface)
            .border(1.dp, K.Stroke, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
        if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = K.TextDim, modifier = Modifier.padding(top = 2.dp))
        if (title != null || subtitle != null) Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) K.Grad else Brush.linearGradient(listOf(K.Surface2, K.Surface2)))
            .border(1.dp, if (selected) Color.Transparent else K.Stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 14.dp, vertical = 8.dp))
    ) {
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtSec(ms: Long) = "%.1fs".format(ms / 1000f)
