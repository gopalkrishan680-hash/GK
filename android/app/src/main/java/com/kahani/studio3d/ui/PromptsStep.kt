package com.kahani.studio3d.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.logic.PromptGenerator
import java.io.File

@Composable
fun PromptsStep(vm: StudioViewModel, p: Project) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val hasKey = vm.prefs.geminiKey.isNotBlank()
    LaunchedEffect(p.id) {
        if (p.scenes.isNotEmpty() && p.scenes.any { it.prompt.isBlank() }) vm.generatePrompts(useAi = hasKey, overwrite = false)
    }
    var start = 0L

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard("Image Prompts", "${p.scenes.size} scenes • ${p.style.label}. Copy them into any AI image generator.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("AI (Gemini)", Icons.Default.AutoAwesome, Modifier.weight(1f)) { vm.generatePrompts(useAi = true, overwrite = true) }
                    SoftButton("Offline", Icons.Default.Bolt, Modifier.weight(1f)) {
                        vm.update { it.copy(scenes = it.scenes.map { s -> s.copy(prompt = "") }) }
                        vm.generatePrompts(useAi = false, overwrite = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Copy All", Modifier.weight(1f), icon = Icons.Default.ContentCopy) {
                        clip.setText(AnnotatedString(PromptGenerator.exportText(p, true)))
                        vm.toast("All ${p.scenes.size} prompts copied")
                    }
                    SoftButton("Share .txt", Icons.Default.Share, Modifier.weight(1f)) {
                        val f = File(ctx.cacheDir, "prompts_${p.id}.txt").apply { writeText(PromptGenerator.exportText(p, true)) }
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_TEXT, PromptGenerator.exportText(p, true))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share prompts"))
                    }
                }
                if (!hasKey) {
                    Spacer(Modifier.height(8.dp))
                    Text("Tip: add a free Gemini key in Settings for detailed, consistent prompts (works even with Hindi scripts).", color = K.Yellow, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text("Save images as 1.png, 2.png, 3.png… and bulk upload will put them in order automatically.", color = K.TextDim, fontSize = 12.sp)
            }
        }
        itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
            val from = p.scenes.take(i).sumOf { it.durationMs }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Surface).border(1.dp, K.Stroke, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(K.Grad), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("${fmtTime(from)} – ${fmtTime(from + s.durationMs)}", color = K.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (s.imagePath != null) Icon(Icons.Default.CheckCircle, null, tint = K.Green, modifier = Modifier.size(18.dp))
                    IconButton(onClick = { vm.updateScene(i) { it.copy(prompt = PromptGenerator.offline(p, i, it)) } }) {
                        Icon(Icons.Default.Refresh, null, tint = K.TextDim)
                    }
                    IconButton(onClick = { clip.setText(AnnotatedString(s.prompt)); vm.toast("Scene ${i + 1} prompt copied") }) {
                        Icon(Icons.Default.ContentCopy, null, tint = K.Cyan)
                    }
                }
                Text("🎙 ${s.text}", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                OutlinedTextField(
                    s.prompt, { v -> vm.updateScene(i) { it.copy(prompt = v) } },
                    modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                    label = { Text("Prompt") }
                )
            }
        }
    }
}
