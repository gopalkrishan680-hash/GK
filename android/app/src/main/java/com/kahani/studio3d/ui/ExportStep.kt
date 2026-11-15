package com.kahani.studio3d.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.Project

@Composable
fun ExportStep(vm: StudioViewModel, p: Project) {
    val ctx = LocalContext.current
    val ex by vm.export.collectAsState()
    var quality by remember { mutableIntStateOf(vm.prefs.exportQuality) }
    var fps by remember { mutableIntStateOf(vm.prefs.exportFps) }
    val (w, h) = p.aspect.size(quality)
    val duration = maxOf(p.totalDurationMs, p.voiceDurationMs)
    val missing = p.scenes.count { it.imagePath == null }

    // keep screen awake while rendering
    DisposableEffect(ex.running) {
        val win = (ctx as? Activity)?.window
        if (ex.running) win?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { win?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Video summary") {
            InfoRow("Length", fmtTime(duration))
            InfoRow("Scenes", "${p.scenes.size}")
            InfoRow("Images", "${p.imagesDone}/${p.scenes.size}")
            InfoRow("Style", p.style.label)
            InfoRow("Size", "${p.aspect.label} • ${w}×${h}")
            InfoRow("Audio", listOfNotNull(p.voiceName?.let { "Voice" }, p.musicName?.let { "Music" }).joinToString(" + ").ifEmpty { "Silent" })
            if (missing > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = K.Yellow)
                    Text("  $missing scenes me image nahi hai", color = K.Yellow, fontSize = 13.sp)
                }
            }
        }
        SectionCard("Quality") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(480 to "480p", 720 to "720p HD", 1080 to "1080p Full HD").forEach { (q, l) -> Pill(l, quality == q) { if (!ex.running) quality = q } }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 30, 60).forEach { f -> Pill("$f fps", fps == f) { if (!ex.running) fps = f } }
            }
        }

        when {
            ex.running -> SectionCard("Render ho raha hai…", "Phone par hi video ban rahi hai. App khula rakhiye.") {
                LinearProgressIndicator(progress = { ex.progress }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)), color = K.Pink, trackColor = K.Stroke)
                Spacer(Modifier.height(8.dp))
                Text("${(ex.progress * 100).toInt()}% • ${ex.label}", color = Color.White)
                Spacer(Modifier.height(10.dp))
                SoftButton("Cancel", Icons.Default.Cancel) { vm.cancelExport() }
            }
            ex.file != null -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = K.Green)
                    Text("  Video ready! Gallery → Movies/Kahani3D", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", ex.file!!)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Play", Modifier.weight(1f), icon = Icons.Default.PlayCircle) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                    SoftButton("Share", Icons.Default.Share, Modifier.weight(1f)) {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Video share karo"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                SoftButton("Dobara export karo", Icons.Default.Movie, Modifier.fillMaxWidth()) { vm.startExport(quality, fps) }
            }
            else -> {
                ex.error?.let { Text("Error: $it\n720p ya 30fps try karein.", color = K.Pink) }
                GradientButton("Export Video (MP4)", Modifier.fillMaxWidth(), icon = Icons.Default.Movie, enabled = p.scenes.isNotEmpty()) { vm.startExport(quality, fps) }
                Text("Video bina watermark ke, phone par hi banti hai — internet ki zarurat nahi.", color = K.TextDim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = K.TextDim, modifier = Modifier.weight(1f))
        Text(v, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}
