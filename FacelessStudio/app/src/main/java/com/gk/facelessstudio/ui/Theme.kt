package com.gk.facelessstudio.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Neon = Color(0xFF8BFF3A)
val Hot = Color(0xFFFF3B5C)
val Violet = Color(0xFF7C5CFF)
val Bg = Color(0xFF0A0D12)
val Surface1 = Color(0xFF131922)
val Surface2 = Color(0xFF1B2330)
val Muted = Color(0xFF8A97A8)

val BrandGradient = Brush.linearGradient(listOf(Color(0xFF7CFF1F), Color(0xFF22E3C8), Color(0xFF7C5CFF)))
val HotGradient = Brush.linearGradient(listOf(Color(0xFFFF3B5C), Color(0xFFFF7A1A)))

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon, onPrimary = Color(0xFF07110A),
            secondary = Hot, tertiary = Violet,
            background = Bg, surface = Surface1, surfaceVariant = Surface2,
            onBackground = Color.White, onSurface = Color.White, onSurfaceVariant = Muted,
            outline = Color(0xFF2A3546),
        ),
        content = content,
    )
}

@Composable
fun Pill(text: String, color: Color = Surface2, textColor: Color = Color.White) {
    Box(Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor))
    }
}

/** Decodes a small thumbnail off the main thread. */
@Composable
fun rememberThumb(path: String?, size: Int = 256): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
