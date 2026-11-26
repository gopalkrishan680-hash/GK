package com.gk.papercut.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gk.papercut.data.CameraMove
import com.gk.papercut.data.EnterAnim
import com.gk.papercut.data.IdleAnim
import com.gk.papercut.data.Project
import com.gk.papercut.data.Scene
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.data.Transition
import com.gk.papercut.render.FrameRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: StudioViewModel, project: Project) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ToastHost(vm)
    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text(project.name, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { vm.close() }) { Icon(Icons.Default.ArrowBack, "Back") } },
            )
            TabRow(selectedTabIndex = tab) {
                listOf("1 Script", "2 Prompts", "3 Layers", "4 Export").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) })
                }
            }
            BusyBar(busy, onCancel = if (busy?.progress != null && tab == 3) ({ vm.cancelExport() }) else null)
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ScriptTab(vm, project) { tab = 1 }
                1 -> PromptsTab(vm, project)
                2 -> LayersTab(vm, project)
                else -> ExportTab(vm, project)
            }
        }
    }
}

// =============================================================================== 1. Script

@Composable
private fun ScriptTab(vm: StudioViewModel, p: Project, onDone: () -> Unit) {
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::importAudio) }
    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::importSrt) }
    var script by remember(p.id) { mutableStateOf(p.script) }
    var chars by remember(p.id) { mutableStateOf(p.characterNote) }
    var style by remember(p.id) { mutableStateOf(p.styleNote) }
    var useAi by remember { mutableStateOf(vm.settings.geminiKey.isNotBlank()) }
    fun saveText() = vm.update { it.copy(script = script, characterNote = chars, styleNote = style) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("Script") {
            Hint("Paste your full narration. Leave an empty line between paragraphs to choose the scene breaks yourself; otherwise the app groups sentences automatically.")
            OutlinedTextField(script, { script = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp),
                placeholder = { Text("Ek chhote se gaon mein Raju naam ka ladka rehta tha…") })
            Hint("${ScriptPlanner.words(script).size} words · about ${ScriptPlanner.splitScenes(script).size} scenes")
        }
        SectionCard("Look & characters (optional)") {
            OutlinedTextField(chars, { chars = it }, Modifier.fillMaxWidth(), label = { Text("Characters") },
                placeholder = { Text("Raju: 10-year-old boy, red t-shirt, messy black hair") })
            OutlinedTextField(style, { style = it }, Modifier.fillMaxWidth(), label = { Text("Style / colours") },
                placeholder = { Text("warm pastel colours, Indian village") })
            Hint("These are added to every image prompt so characters stay consistent across scenes.")
        }
        SectionCard("Voice-over") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (p.audioFile != null) "Voice-over added · ${fmtTime(p.audioDuration)}" else "No voice-over yet")
                    Text(if (p.srtFile != null) "Subtitles (SRT) added – exact timing" else "No SRT – timing from voice pauses", fontSize = 13.sp)
                }
            }
            FlowRowButtons {
                Button(onClick = { audioPicker.launch("audio/*") }) { Text(if (p.audioFile == null) "Add voice-over" else "Replace voice-over") }
                OutlinedButton(onClick = { srtPicker.launch("*/*") }) { Text("Add SRT (optional)") }
                if (p.srtFile != null) TextButton(onClick = { vm.clearSrt() }) { Text("Remove SRT") }
            }
        }
        SectionCard("Build scenes") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useAi = !useAi }) {
                Checkbox(useAi, { useAi = it })
                Text("Use AI Director (Gemini) for richer prompts" + if (vm.settings.geminiKey.isBlank()) " – add key in Settings" else "")
            }
            FlowRowButtons {
                Button(onClick = { saveText(); vm.buildScenes(useAi); onDone() }) { Text(if (p.scenes.isEmpty()) "Create scenes & prompts" else "Re-create scenes") }
                if (p.scenes.isNotEmpty()) OutlinedButton(onClick = { saveText(); vm.resync() }) { Text("Re-sync timing only") }
            }
            if (p.scenes.isNotEmpty()) Hint("Re-creating scenes replaces the current scenes and their images. Use “Re-sync timing only” after changing the voice-over.")
        }
        Spacer(Modifier.height(40.dp))
    }
    DisposableEffect(p.id) { onDispose { if (script != p.script || chars != p.characterNote || style != p.styleNote) saveText() } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

// =============================================================================== 2. Prompts

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", text))
    android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun PromptsTab(vm: StudioViewModel, p: Project) {
    val ctx = LocalContext.current
    if (p.scenes.isEmpty()) { EmptyState("Create scenes in step 1 first."); return }
    var target by remember { mutableStateOf<String?>(null) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> val t = target; if (uri != null && t != null) vm.importImage(t, uri) }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { if (it.isNotEmpty()) vm.importMany(it) }

    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        item {
            SectionCard("Image prompts") {
                Hint("Copy each prompt into any image generator (Gemini, ChatGPT, Ideogram, Leonardo…). Save the images as scene_1, scene_2 … and import them – one by one or all together.")
                FlowRowButtons {
                    Button(onClick = { copy(ctx, ScriptPlanner.allPromptsText(p)) }) { Text("Copy all prompts") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, ScriptPlanner.allPromptsText(p)), "Share prompts"))
                    }) { Text("Share") }
                    OutlinedButton(onClick = { copy(ctx, ScriptPlanner.chatbotHelperPrompt(p, p.scenes)) }) { Text("Copy chatbot helper") }
                    FilledTonalButton(onClick = { many.launch("image/*") }) { Text("Import all images") }
                }
                Hint("“Chatbot helper” is one message you can paste into ChatGPT/Gemini chat to get even more detailed prompts for all scenes.")
            }
        }
        itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
            SceneCard(vm, p, i, s, onImport = { target = s.id; one.launch("image/*") }, onCopy = { copy(ctx, s.prompt) })
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun SceneCard(vm: StudioViewModel, p: Project, i: Int, s: Scene, onImport: () -> Unit, onCopy: () -> Unit) {
    var edit by remember(s.id) { mutableStateOf(false) }
    var prompt by remember(s.id, s.prompt) { mutableStateOf(s.prompt) }
    val img = rememberProjectBitmap(vm, p.id, s.imageFile)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scene ${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${fmtTime(s.start)} – ${fmtTime(s.end)}", fontSize = 13.sp)
        }
        Text("“${s.text}”", fontSize = 14.sp)
        if (s.elementsHint.isNotEmpty()) Hint("Elements: " + s.elementsHint.joinToString { it.name })
        if (edit) {
            OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth())
            Row { TextButton(onClick = { vm.setPrompt(s.id, prompt); edit = false }) { Text("Save prompt") } }
        } else {
            Text(s.prompt, fontSize = 12.sp, color = Color(0xFF5A4A3A), modifier = Modifier.background(Color(0xFFF3EAD8)).padding(8.dp).clickable { edit = true })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCopy) { Text("Copy") }
            OutlinedButton(onClick = onImport) { Text(if (img == null) "Import image" else "Replace") }
            if (img != null) Image(img.asImageBitmap(), null, Modifier.size(64.dp))
        }
        if (i > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("Start time")
            TextButton(onClick = { vm.nudge(i, -0.2) }) { Text("−0.2s") }
            TextButton(onClick = { vm.nudge(i, 0.2) }) { Text("+0.2s") }
        }
    }
}

// =============================================================================== 3. Layers

@Composable
private fun LayersTab(vm: StudioViewModel, p: Project) {
    if (p.scenes.isEmpty()) { EmptyState("Create scenes in step 1 first."); return }
    var useSam by remember { mutableStateOf(vm.models.samReady) }
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.padding(horizontal = 12.dp)) {
        item {
            SectionCard("Separate elements (offline)") {
                Hint("ML Kit finds the main subjects; MobileSAM finds extra objects; the background behind them is repainted (LaMa if downloaded). Then every element animates on its own, timed to the words of your voice-over.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(useSam && vm.models.samReady, { useSam = it }, enabled = vm.models.samReady)
                    Spacer(Modifier.width(8.dp))
                    Text(if (vm.models.samReady) "Deep scan with MobileSAM" else "MobileSAM not downloaded (Settings)", fontSize = 14.sp)
                }
                Button(onClick = { vm.separateAll(useSam) }, enabled = p.scenes.any { it.imageFile != null }) { Text("Separate all scenes") }
            }
        }
        itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { open = if (open == s.id) null else s.id }) {
                    Text("Scene ${i + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            s.imageFile == null -> "No image"
                            s.backgroundFile == null -> "Not separated"
                            else -> "${s.layers.size} elements"
                        }, fontSize = 13.sp,
                    )
                    Icon(if (open == s.id) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                }
                if (open == s.id) LayerEditor(vm, p, s, useSam)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun LayerEditor(vm: StudioViewModel, p: Project, s: Scene, useSam: Boolean) {
    if (s.imageFile == null) { Hint("Import an image for this scene in step 2."); return }
    val img = rememberProjectBitmap(vm, p.id, s.imageFile)
    val bg = rememberProjectBitmap(vm, p.id, s.backgroundFile)
    var showBg by remember { mutableStateOf(false) }
    val shown = if (showBg && bg != null) bg else img
    if (shown != null) {
        Box(Modifier.fillMaxWidth().aspectRatio(shown.width / shown.height.toFloat())) {
            Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().pointerInput(s.id, shown) {
                detectTapGestures { o -> vm.tapToCut(s.id, o.x / size.width * shown.width, o.y / size.height * shown.height) }
            }) {
                val k = size.width / shown.width
                s.layers.forEach { l ->
                    drawRect(Color(0xFFD9542F), Offset(l.x * k, l.y * k), Size(l.w * k, l.h * k), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Hint("Tap any object in the picture to cut it out as a new element (MobileSAM).")
    }
    FlowRowButtons {
        Button(onClick = { vm.separate(s.id, useSam) }) { Text(if (s.backgroundFile == null) "Separate" else "Separate again") }
        if (bg != null) OutlinedButton(onClick = { showBg = !showBg }) { Text(if (showBg) "Show original" else "Show clean background") }
    }
    FlowRowButtons {
        Picker("Camera", s.camera, CameraMove.entries, { it.label }, { c -> vm.setScene(s.id) { it.copy(camera = c) } })
        Picker("Transition in", s.transition, Transition.entries, { it.label }, { t -> vm.setScene(s.id) { it.copy(transition = t) } })
    }
    val words = s.words.map { it.text }.ifEmpty { ScriptPlanner.words(s.text) }
    s.layers.asReversed().forEach { l ->
        val thumb = rememberProjectBitmap(vm, p.id, l.file)
        Column(Modifier.fillMaxWidth().background(Color(0xFFF7F0E2)).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.size(56.dp))
                Spacer(Modifier.width(8.dp))
                Text(l.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { vm.moveLayer(s.id, l.id, true) }) { Icon(Icons.Default.KeyboardArrowUp, "Bring forward") }
                IconButton(onClick = { vm.moveLayer(s.id, l.id, false) }) { Icon(Icons.Default.KeyboardArrowDown, "Send back") }
                IconButton(onClick = { vm.deleteLayer(s.id, l.id) }) { Icon(Icons.Default.Delete, "Remove") }
            }
            FlowRowButtons {
                Picker("In", l.enter, EnterAnim.entries, { it.label }, { e -> vm.updateLayer(s.id, l.id) { it.copy(enter = e) } })
                Picker("Loop", l.idle, IdleAnim.entries, { it.label }, { e -> vm.updateLayer(s.id, l.id) { it.copy(idle = e) } })
                Picker("Appears on word", l.keywordIndex, listOf(-1) + words.indices.toList(),
                    { if (it < 0) "auto" else "“${words.getOrElse(it) { "?" }}”" },
                    { k -> vm.updateLayer(s.id, l.id) { it.copy(keywordIndex = k) } })
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

// =============================================================================== 4. Preview & export

@Composable
private fun ExportTab(vm: StudioViewModel, p: Project) {
    val ctx = LocalContext.current
    val exported by vm.exported.collectAsStateWithLifecycle()
    if (p.scenes.isEmpty()) { EmptyState("Create scenes in step 1 first."); return }
    val renderer = remember(p) { FrameRenderer(p, vm.repo) }
    var t by remember { mutableDoubleStateOf(0.0) }
    var playing by remember { mutableStateOf(false) }
    val player = remember(p.audioFile) {
        p.audioFile?.let { f -> runCatching { MediaPlayer().apply { setDataSource(vm.repo.file(p.id, f).path); prepare() } }.getOrNull() }
    }
    DisposableEffect(player) { onDispose { player?.release() } }

    LaunchedEffect(playing) {
        if (!playing) { player?.takeIf { it.isPlaying }?.pause(); return@LaunchedEffect }
        player?.let { it.seekTo((t * 1000).toInt()); it.start() }
        val t0 = t; var start = -1L
        while (playing) {
            withFrameNanos { now ->
                if (start < 0) start = now
                t = player?.currentPosition?.div(1000.0) ?: (t0 + (now - start) / 1e9)
            }
            if (t >= renderer.duration) { playing = false; t = 0.0 }
        }
    }
    val sceneIdx = renderer.sceneIndexAt(t)
    LaunchedEffect(sceneIdx, renderer) { withContext(Dispatchers.Default) { renderer.prefetch(t) } }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth(if (p.aspect.w < p.aspect.h) 0.6f else 1f).align(Alignment.CenterHorizontally)
                .aspectRatio(renderer.outW / renderer.outH.toFloat()).background(Color(0xFFF6EEDC))
        ) {
            val time = t
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save(); nc.scale(size.width / renderer.outW, size.height / renderer.outH)
                renderer.render(nc, time)
                nc.restore()
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play") }
            Spacer(Modifier.width(8.dp))
            Text("${fmtTime(t)} / ${fmtTime(renderer.duration)} · scene ${sceneIdx + 1}", fontSize = 13.sp)
        }
        Slider(value = t.toFloat(), onValueChange = { playing = false; t = it.toDouble() },
            valueRange = 0f..renderer.duration.toFloat().coerceAtLeast(0.1f))

        SectionCard("Style") {
            Toggle("Animated captions (word highlight)", p.captions) { v -> vm.update { it.copy(captions = v) } }
            Toggle("Stop-motion feel (12 fps steps + paper wobble)", p.stopMotion) { v -> vm.update { it.copy(stopMotion = v) } }
            Toggle("Paper texture, white edges & shadows", p.paperTexture) { v -> vm.update { it.copy(paperTexture = v) } }
        }
        SectionCard("Export MP4") {
            Hint("${p.aspect.w}×${p.aspect.h}, 30 fps, voice-over included. Rendering runs on the phone; keep the app open.")
            Button(onClick = { playing = false; vm.export() }) { Text("Export video") }
            exported?.let { f ->
                Text("Ready: ${f.name} (${f.length() / 1_000_000} MB)")
                FlowRowButtons {
                    Button(onClick = { vm.saveToGallery(f) }) { Text("Save to gallery") }
                    OutlinedButton(onClick = { ctx.startActivity(Intent.createChooser(vm.shareIntent(f), "Share video")) }) { Text("Share") }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!value) }) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}
