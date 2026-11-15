package com.kahani.studio3d.ui

import android.graphics.Bitmap
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.CaptionPosition
import com.kahani.studio3d.data.CaptionStyle
import com.kahani.studio3d.data.ColorFilter
import com.kahani.studio3d.data.Motion
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.Transition
import com.kahani.studio3d.render.FrameRenderer
import java.io.File

class PreviewController {
    var timeMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
}

@Composable
fun EditStep(vm: StudioViewModel, p: Project) {
    val ctl = remember { PreviewController() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Scenes", "Captions", "Effects", "Audio")
    Column(Modifier.fillMaxSize()) {
        PreviewPlayer(p, ctl, Modifier.fillMaxWidth().heightIn(max = 270.dp).padding(horizontal = 16.dp, vertical = 6.dp))
        TabRow(
            selectedTabIndex = tab, containerColor = K.Bg, contentColor = Color.White,
            indicator = { pos -> TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(pos[tab]), color = K.Pink) }
        ) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ScenesTab(vm, p, ctl)
                1 -> CaptionsTab(vm, p)
                2 -> EffectsTab(vm, p)
                else -> AudioTab(vm, p)
            }
        }
    }
}

@Composable
fun PreviewPlayer(p: Project, ctl: PreviewController, modifier: Modifier) {
    val (w, h) = remember(p.aspect) { p.aspect.size(360) }
    val renderer = remember(p.id, p.aspect) { FrameRenderer(p, w, h) }
    val bmp = remember(w, h) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val canvas = remember(bmp) { android.graphics.Canvas(bmp) }
    val image = remember(bmp) { bmp.asImageBitmap() }
    renderer.setProject(p)
    val duration = renderer.durationMs

    val player = remember(p.voicePath) {
        p.voicePath?.let { path -> runCatching { MediaPlayer().apply { setDataSource(path); prepare() } }.getOrNull() }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    DisposableEffect(renderer) { onDispose { renderer.release() } }

    LaunchedEffect(ctl.playing, player) {
        if (!ctl.playing) { player?.takeIf { it.isPlaying }?.pause(); return@LaunchedEffect }
        if (ctl.timeMs >= duration - 50) ctl.timeMs = 0
        val startClock = System.currentTimeMillis()
        val startT = ctl.timeMs
        if (player != null && startT < player.duration) { player.seekTo(startT.toInt()); player.start() }
        while (ctl.playing) {
            withFrameMillis { }
            val t = if (player != null && player.isPlaying) player.currentPosition.toLong()
            else startT + (System.currentTimeMillis() - startClock)
            ctl.timeMs = t.coerceAtMost(duration)
            if (t >= duration) { ctl.playing = false }
        }
        player?.takeIf { it.isPlaying }?.pause()
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f, fill = false).aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .clickable { ctl.playing = !ctl.playing }) {
            Canvas(Modifier.fillMaxSize()) {
                val t = ctl.timeMs // read state -> redraw on change
                renderer.draw(canvas, t)
                drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
            }
            if (!ctl.playing) Box(Modifier.align(Alignment.Center).size(56.dp).clip(RoundedCornerShape(50)).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ctl.playing = !ctl.playing }) {
                Icon(if (ctl.playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White)
            }
            Slider(
                value = if (duration > 0) ctl.timeMs.toFloat() / duration else 0f,
                onValueChange = { ctl.playing = false; ctl.timeMs = (it * duration).toLong() },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = K.Pink, activeTrackColor = K.Violet, inactiveTrackColor = K.Stroke)
            )
            Text("${fmtTime(ctl.timeMs)} / ${fmtTime(duration)}", color = K.TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ScenesTab(vm: StudioViewModel, p: Project, ctl: PreviewController) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Re-sync to voice", Icons.Default.Sync, Modifier.weight(1f)) { vm.resync(); vm.toast("Timing reset to match the voiceover") }
                var allMotion by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    SoftButton("Motion for all", null, Modifier.fillMaxWidth()) { allMotion = true }
                    DropdownMenu(allMotion, { allMotion = false }) {
                        Motion.entries.forEach { m -> DropdownMenuItem(text = { Text(m.label) }, onClick = { allMotion = false; vm.update { it.copy(scenes = it.scenes.map { s -> s.copy(motion = m) }) } }) }
                    }
                }
            }
        }
        itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
            val start = p.scenes.take(i).sumOf { it.durationMs }
            val active = ctl.timeMs >= start && ctl.timeMs < start + s.durationMs
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (active) K.Surface2 else K.Surface)
                    .border(1.dp, if (active) K.Violet else K.Stroke, RoundedCornerShape(14.dp))
                    .clickable { ctl.playing = false; ctl.timeMs = start + 1 }.padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(K.Surface2)) {
                        s.imagePath?.let { AsyncImage(File(it), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        Text("${i + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(2.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xAA000000)).padding(horizontal = 4.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(s.text, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = K.TextDim) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Merge with next") }, leadingIcon = { Icon(Icons.Default.CallMerge, null) }, onClick = { menu = false; vm.mergeWithNext(i) })
                            DropdownMenuItem(text = { Text("Split in two") }, leadingIcon = { Icon(Icons.Default.CallSplit, null) }, onClick = { menu = false; vm.splitScene(i) })
                            DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, onClick = { menu = false; vm.moveScene(i, -1) })
                            DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, onClick = { menu = false; vm.moveScene(i, 1) })
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; vm.deleteScene(i) })
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.updateScene(i) { it.copy(durationMs = (it.durationMs - 250).coerceAtLeast(500), locked = true) } }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, null, tint = Color.White)
                    }
                    Text(fmtSec(s.durationMs), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    IconButton(onClick = { vm.updateScene(i) { it.copy(durationMs = it.durationMs + 250, locked = true) } }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, null, tint = Color.White)
                    }
                    if (s.locked) Icon(Icons.Default.Lock, null, tint = K.Yellow, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.weight(1f))
                    EnumMenu("🎥 " + s.motion.label, Motion.entries, { it.label }) { m -> vm.updateScene(i) { it.copy(motion = m) } }
                    Spacer(Modifier.width(6.dp))
                    EnumMenu("✨ " + (s.transition?.label ?: "Default"), listOf<Transition?>(null) + Transition.entries, { it?.label ?: "Default" }) { t -> vm.updateScene(i) { it.copy(transition = t) } }
                }
            }
        }
    }
}

@Composable
private fun <T> EnumMenu(label: String, items: List<T>, name: (T) -> String, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(label, color = K.Cyan, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(K.Bg).clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp))
        DropdownMenu(open, { open = false }) {
            items.forEach { it -> DropdownMenuItem(text = { Text(name(it)) }, onClick = { open = false; onPick(it) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionsTab(vm: StudioViewModel, p: Project) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Caption style", "Auto captions from your script, timed word by word") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptionStyle.entries.forEach { c -> Pill(c.label, p.captionStyle == c) { vm.update { it.copy(captionStyle = c) } } }
            }
        }
        SectionCard("Position") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptionPosition.entries.forEach { c -> Pill(c.label, p.captionPosition == c) { vm.update { it.copy(captionPosition = c) } } }
            }
        }
        SectionCard("Size: ${"%.0f".format(p.captionSize * 100)}%") {
            Slider(p.captionSize, { v -> vm.update { it.copy(captionSize = v) } }, valueRange = 0.6f..1.6f)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EffectsTab(vm: StudioViewModel, p: Project) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Default transition") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Transition.entries.forEach { t -> Pill(t.label, p.transition == t) { vm.update { it.copy(transition = t) } } }
            }
            Spacer(Modifier.height(8.dp))
            Text("Transition time: ${p.transitionMs} ms", color = K.TextDim, fontSize = 12.sp)
            Slider(p.transitionMs.toFloat(), { v -> vm.update { it.copy(transitionMs = v.toLong()) } }, valueRange = 100f..1200f)
        }
        SectionCard("3D Camera motion strength: ${"%.0f".format(p.motionIntensity * 100)}%") {
            Slider(p.motionIntensity, { v -> vm.update { it.copy(motionIntensity = v) } }, valueRange = 0.2f..2f)
        }
        SectionCard("Color filter") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorFilter.entries.forEach { c -> Pill(c.label, p.colorFilter == c) { vm.update { it.copy(colorFilter = c) } } }
            }
        }
        SectionCard("Cinematic") {
            ToggleRow("Vignette (dark edges)", p.vignette) { v -> vm.update { it.copy(vignette = v) } }
            ToggleRow("Letterbox (movie bars)", p.letterbox) { v -> vm.update { it.copy(letterbox = v) } }
            ToggleRow("Film grain", p.filmGrain) { v -> vm.update { it.copy(filmGrain = v) } }
        }
        SectionCard("Watermark / channel name") {
            OutlinedTextField(p.watermark, { v -> vm.update { it.copy(watermark = v) } }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("@MyChannel") })
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

@Composable
private fun AudioTab(vm: StudioViewModel, p: Project) {
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::importMusic) }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::importVoice) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Voiceover", p.voiceName?.let { "$it • ${fmtTime(p.voiceDurationMs)}" } ?: "No voiceover") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(if (p.voicePath == null) "Upload" else "Change", null, Modifier.weight(1f)) { pickVoice.launch("audio/*") }
                SoftButton("Re-sync scenes", Icons.Default.Sync, Modifier.weight(1f)) { vm.resync() }
            }
        }
        SectionCard("Background music", p.musicName ?: "Optional — soft music under the voice") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(if (p.musicPath == null) "Add music" else "Change", Icons.Default.MusicNote, Modifier.weight(1f)) { pickMusic.launch("audio/*") }
                if (p.musicPath != null) SoftButton("Remove", Icons.Default.Delete, Modifier.weight(1f)) { vm.removeMusic() }
            }
            if (p.musicPath != null) {
                Spacer(Modifier.height(10.dp))
                Text("Music volume: ${"%.0f".format(p.musicVolume * 100)}%", color = K.TextDim, fontSize = 12.sp)
                Slider(p.musicVolume, { v -> vm.update { it.copy(musicVolume = v) } }, valueRange = 0f..1f)
                ToggleRow("Auto-duck (lower music while speaking)", p.duckMusic) { v -> vm.update { it.copy(duckMusic = v) } }
                Text("Music doesn't play in preview; it's mixed into the exported video.", color = K.TextDim, fontSize = 11.sp)
            }
        }
    }
}
