package com.gk.facelessstudio.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.gk.facelessstudio.export.ExportManager
import com.gk.facelessstudio.export.ExportState
import com.gk.facelessstudio.model.Quality
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.Transition
import com.gk.facelessstudio.render.Themes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel, onBack: () -> Unit, onEditSetup: (Int) -> Unit) {
    val p by vm.project.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    val preview = remember { PreviewView(ctx) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(p) { preview.bind(p, vm.fonts) }
    DisposableEffect(Unit) {
        preview.onTime = { t, pl -> pos = t; playing = pl }
        onDispose { preview.release() }
    }
    val curScene = p.scenes.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
    LaunchedEffect(curScene, playing) { if (playing && p.scenes.isNotEmpty()) listState.animateScrollToItem(curScene) }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(p.title.ifBlank { "Editor" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { preview.pause(); onEditSetup(3) }) { Icon(Icons.Default.Tune, "Settings") }
                    Box(Modifier.padding(end = 10.dp).clip(RoundedCornerShape(12.dp)).background(BrandGradient)
                        .clickable { preview.pause(); showExport = true }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Download, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Export", color = Color.Black, fontWeight = FontWeight.Black)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // ---------------- preview
            val ratio = p.aspect.ratioW / p.aspect.ratioH.toFloat()
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).then(if (ratio < 1f) Modifier.height(340.dp) else Modifier), contentAlignment = Alignment.Center) {
                Box(Modifier.then(if (ratio < 1f) Modifier.fillMaxSize().aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.fillMaxWidth().aspectRatio(ratio))
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable { preview.toggle() }) {
                    AndroidView({ preview }, Modifier.fillMaxSize())
                    if (!playing) Box(Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(40.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { preview.toggle() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
                Text(fmtTime(pos), fontSize = 12.sp)
                Slider(
                    value = if (p.durationMs > 0) pos / p.durationMs.toFloat() else 0f,
                    onValueChange = { preview.seek((it * p.durationMs).toLong()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(fmtTime(p.durationMs), fontSize = 12.sp)
            }
            // ---------------- quick actions
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { preview.pause(); vm.shuffle() }, enabled = busy == null, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle animations")
                }
                Themes.all.forEach { t ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(t.accent)).clickable { vm.setTheme(t.id) }, contentAlignment = Alignment.Center) {
                        if (p.themeId == t.id) Icon(Icons.Default.CheckCircle, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    }
                }
            }
            ExportBanner(vm)
            Text("${p.scenes.size} scenes  •  tap to jump, use chips to change layout", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(p.scenes, key = { i, s -> "$i-${s.startMs}" }) { i, s ->
                    SceneCard(vm, i, active = i == curScene, onJump = { preview.seek(s.startMs) })
                }
            }
        }
    }
    if (showExport) ExportSheet(vm) { showExport = false }
    AiOverlay(vm)
}

@Composable
private fun SceneCard(vm: AppViewModel, i: Int, active: Boolean, onJump: () -> Unit) {
    val p by vm.project.collectAsState()
    val s = p.scenes.getOrNull(i) ?: return
    val thumb = rememberThumb(p.images.getOrNull(s.imageIndex), 200)
    var typeMenu by remember { mutableStateOf(false) }
    var trMenu by remember { mutableStateOf(false) }
    var imgMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) Surface2 else Surface1).clickable(onClick = onJump).padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                if (thumb != null && (s.type.needsImage || s.type in listOf(SceneType.DATE_STAMP, SceneType.QUESTION_LIST, SceneType.SPLIT_ARROW)))
                    Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("${i + 1}", fontWeight = FontWeight.Black, color = Neon)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${fmtTime(s.startMs)} – ${fmtTime(s.endMs)}", color = if (active) Neon else Muted, fontSize = 11.sp)
                Text(s.text, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.regenerateSceneImage(i) }) { Icon(Icons.Default.AutoAwesome, "New AI image", tint = Violet) }
            IconButton(onClick = { vm.reroll(i) }) { Icon(Icons.Default.Casino, "Re-roll", tint = Neon) }
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                FilterChip(selected = true, onClick = { typeMenu = true }, label = { Text(s.type.label, fontSize = 12.sp) })
                DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                    SceneType.entries.filter { it != SceneType.OUTRO }.forEach { t ->
                        DropdownMenuItem(text = { Text(t.label + if (t.needsImage) "  🖼" else "") }, onClick = { typeMenu = false; vm.setSceneType(i, t) })
                    }
                }
            }
            Box {
                FilterChip(selected = false, onClick = { trMenu = true }, label = { Text("↪ " + s.transition.label, fontSize = 12.sp) })
                DropdownMenu(trMenu, onDismissRequest = { trMenu = false }) {
                    Transition.entries.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { trMenu = false; vm.setSceneTransition(i, t) }) }
                }
            }
            if (p.images.isNotEmpty()) Box {
                FilterChip(selected = false, onClick = { imgMenu = true }, label = { Text(if (s.imageIndex >= 0) "Image ${s.imageIndex + 1}" else "Image", fontSize = 12.sp) })
                DropdownMenu(imgMenu, onDismissRequest = { imgMenu = false }) {
                    p.images.indices.forEach { ii -> DropdownMenuItem(text = { Text("Image ${ii + 1}") }, onClick = { imgMenu = false; vm.setSceneImage(i, ii) }) }
                }
            }
        }
    }
}

@Composable
private fun ExportBanner(vm: AppViewModel) {
    val st by ExportManager.flow.collectAsState()
    val p by vm.project.collectAsState()
    val ctx = LocalContext.current
    when (val s = st) {
        is ExportState.Running -> if (s.projectId == p.id) Column(Modifier.fillMaxWidth().padding(12.dp).background(Surface1, RoundedCornerShape(14.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rendering ${(s.progress * 100).toInt()}%", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { ExportManager.cancel() }) { Text("Cancel", color = Hot) }
            }
            LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
            Text(s.message, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
        is ExportState.Done -> if (s.projectId == p.id) Row(Modifier.fillMaxWidth().padding(12.dp).background(Neon.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Neon)
            Spacer(Modifier.width(8.dp))
            Text("Saved to gallery! (Movies/FacelessStudio)", Modifier.weight(1f), fontSize = 13.sp)
            IconButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }) { Icon(Icons.Default.PlayArrow, null) }
            IconButton(onClick = { share(ctx, s.uri, "video/mp4") }) { Icon(Icons.Default.Share, null) }
        }
        is ExportState.Failed -> if (s.projectId == p.id) AlertDialog(
            onDismissRequest = { ExportManager.reset() }, confirmButton = { TextButton(onClick = { ExportManager.reset() }) { Text("OK") } },
            title = { Text("Export failed") }, text = { Text(s.error) })
        else -> {}
    }
}

private fun share(ctx: Context, uri: Uri, mime: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(vm: AppViewModel, onClose: () -> Unit) {
    val p by vm.project.collectAsState()
    val ctx = LocalContext.current
    var quality by remember { mutableStateOf(Quality.HD) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ExportManager.start(ctx, p.id, quality); onClose()
    }
    fun copy(label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        vm.toast("$label copied ✅")
    }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Surface1) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Export Video", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.entries.forEach { q -> FilterChip(selected = q == quality, onClick = { quality = q }, label = { Text(q.label) }, modifier = Modifier.weight(1f)) }
            }
            val (w, h) = p.aspect.size(quality)
            Text("${w}×${h}  •  30 fps  •  ${fmtTime(p.durationMs)}  •  MP4 (H.264 + AAC)", color = Muted, fontSize = 12.sp)
            Text("Rendering runs in the background — you can leave the app. Progress shows in the notification.", color = Muted, fontSize = 12.sp)
            Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(BrandGradient).clickable {
                if (p.scenes.isEmpty()) { vm.toast("Generate the video first"); return@clickable }
                vm.saveNow()
                if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                else { ExportManager.start(ctx, p.id, quality); onClose() }
            }, contentAlignment = Alignment.Center) {
                Text("🎬  Start rendering", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Text("Extras for YouTube upload", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val f = vm.writeShareFile("${p.title.ifBlank { "captions" }.replace(Regex("[^A-Za-z0-9]"), "_")}.srt", vm.srt())
                    share(ctx, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f), "application/x-subrip")
                }, modifier = Modifier.weight(1f)) { Text("Captions (.srt)") }
                OutlinedButton(onClick = {
                    val ch = vm.youtubeChapters()
                    if (ch.isBlank()) vm.toast("Add # headings to your script to get chapters") else copy("Chapters", ch)
                }, modifier = Modifier.weight(1f)) { Text("YT Chapters") }
            }
            OutlinedButton(onClick = { vm.shuffle() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try new animations before rendering")
            }
        }
    }
}
