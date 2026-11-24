package com.gk.facelessstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.facelessstudio.model.Aspect
import com.gk.facelessstudio.model.Pacing
import com.gk.facelessstudio.render.Themes

private val STEPS = listOf("Script", "Voice", "Images", "Style")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: AppViewModel, startStep: Int, onBack: () -> Unit, onGenerated: () -> Unit) {
    var step by remember { mutableIntStateOf(startStep.coerceIn(0, 3)) }
    val busy by vm.busy.collectAsState()
    BackHandler { if (step > 0) step-- else onBack() }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("New Video", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = { if (step > 0) step-- else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().background(Surface1).navigationBarsPadding().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.height(54.dp)) { Text("Back") }
                if (step < 3) {
                    Button(onClick = { step++ }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Next: ${STEPS[step + 1]}", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                } else {
                    Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(16.dp)).background(BrandGradient)
                        .clickable(enabled = busy == null) { vm.generate(onGenerated) }, contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AutoAwesome, null, tint = Color.Black)
                            Spacer(Modifier.width(8.dp))
                            Text("Generate Video", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            StepBar(step) { step = it }
            Box(Modifier.weight(1f)) {
                when (step) {
                    0 -> ScriptStep(vm)
                    1 -> VoiceStep(vm)
                    2 -> ImagesStep(vm)
                    else -> StyleStep(vm)
                }
                busy?.let { msg ->
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Neon)
                            Spacer(Modifier.height(14.dp))
                            Text(msg, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBar(step: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        STEPS.forEachIndexed { i, label ->
            val active = i <= step
            Column(Modifier.weight(1f).clickable { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (i == step) BrandGradient else Brush.linearGradient(listOf(if (active) Neon.copy(alpha = 0.35f) else Surface2, if (active) Neon.copy(alpha = 0.35f) else Surface2))),
                    contentAlignment = Alignment.Center
                ) { Text("${i + 1}", color = if (i == step) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                Text(label, fontSize = 11.sp, color = if (active) Color.White else Muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, tint = Neon, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

// ------------------------------------------------------------------ STEP 1
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptStep(vm: AppViewModel) {
    val p by vm.project.collectAsState()
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(p.title, vm::setTitle, label = { Text("Video title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Box {
            OutlinedTextField(
                p.script, vm::setScript,
                label = { Text("Paste your script here") },
                placeholder = { Text("# Intro\nWhat exactly is the dark web?\nWho uses it? When did it start?\n...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val words = p.script.split(Regex("\\s+")).count { it.isNotBlank() }
            Pill("$words words"); Spacer(Modifier.width(8.dp)); Pill("~${fmtTime((words / 2.5f * 1000).toLong())} video")
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { vm.setScript(if (p.script.isBlank()) it else p.script + "\n" + it) }
            }) { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste") }
        }
        SectionCard("Pro tips (optional)", Icons.Default.Lightbulb) {
            val tips = listOf(
                "# Heading" to "New chapter → Checklist / chapter title",
                "What? When? How?" to "Back-to-back questions → Typewriter list",
                "723,335 / 50% / ₹500" to "Number → Counter animation",
                "3 Dec 1984, 12:00 AM" to "Date → Date stamp + digital clock",
                "A vs B" to "Versus split screen",
                "X: a, b and c" to "Mind-map tree",
                "\"quote\"" to "Quote animation",
                "because / so" to "Cause → Effect arrow",
                "[stat] [list] [quote] [note] [phone] [headline] [image]" to "Start a line with a tag to pick the layout yourself",
                "---" to "Hard cut",
            )
            tips.forEach { (a, b) ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(a, color = Neon, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.45f))
                    Text(b, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(0.55f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ STEP 2
@Composable
private fun VoiceStep(vm: AppViewModel) {
    val p by vm.project.collectAsState()
    val info by vm.voiceInfo.collectAsState()
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::pickVoice) }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::pickMusic) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Voiceover", Icons.Default.Mic) {
            if (p.voiceName == null) {
                BigPickButton("Choose voiceover file (mp3, m4a, wav)") { voicePicker.launch("audio/*") }
                Text("The app detects pauses in your voice and syncs every line to the right moment.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                FileRow(p.voiceName!!, info?.let { "${fmtTime(it.durationMs)}  •  ${it.pauses.size} pauses found" } ?: "", onRemove = vm::removeVoice, onReplace = { voicePicker.launch("audio/*") })
            }
        }
        SectionCard("Background music (optional)", Icons.Default.MusicNote) {
            if (p.musicName == null) BigPickButton("Choose music file") { musicPicker.launch("audio/*") }
            else {
                FileRow(p.musicName!!, "Auto-ducks under the voice", onRemove = vm::removeMusic, onReplace = { musicPicker.launch("audio/*") })
                Spacer(Modifier.height(8.dp))
                Text("Music volume: ${(p.musicVolume * 100).toInt()}%", fontSize = 13.sp)
                Slider(p.musicVolume, vm::setMusicVolume, valueRange = 0f..0.6f)
            }
            Text("Tip: get copyright-free music from the YouTube Audio Library.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (p.voiceName == null) Text("No voiceover yet? No problem — the preview uses auto timing, you can add it later.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun BigPickButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(14.dp)).border(BorderStroke(1.5.dp, Neon.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
            .background(Neon.copy(alpha = 0.06f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text("＋  $label", color = Neon, fontWeight = FontWeight.Bold) }
}

@Composable
private fun FileRow(name: String, sub: String, onRemove: () -> Unit, onReplace: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, color = Neon, fontSize = 12.sp)
        }
        androidx.compose.material3.TextButton(onClick = onReplace) { Text("Change") }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, null) }
    }
}

// ------------------------------------------------------------------ STEP 4
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleStep(vm: AppViewModel) {
    val p by vm.project.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Video type") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(true to ("🎬  Story" to "Full-screen cinematic pictures, real subtitle lines, soft fades. For stories, history, mythology."),
                    false to ("📊  Explainer" to "Infographics: charts, lists, checklists, phone & banners. For facts, tips, tutorials.")).forEach { (story, info) ->
                    val sel = p.storyMode == story
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) Neon.copy(alpha = 0.15f) else Surface2)
                        .border(2.dp, if (sel) Neon else Color.Transparent, RoundedCornerShape(14.dp)).clickable { vm.setStoryMode(story) }.padding(12.dp)) {
                        Text(info.first, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(info.second, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        if (p.storyMode) SectionCard("Story style") { StoryStylePicker(vm) }
        SectionCard("Video size") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Aspect.entries.forEach { a ->
                    val sel = p.aspect == a
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) Neon.copy(alpha = 0.15f) else Surface2)
                        .border(2.dp, if (sel) Neon else Color.Transparent, RoundedCornerShape(14.dp)).clickable { vm.setAspect(a) }.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(width = (40 * a.ratioW / maxOf(a.ratioW, a.ratioH)).dp, height = (40 * a.ratioH / maxOf(a.ratioW, a.ratioH)).dp)
                                .border(2.dp, if (sel) Neon else Muted, RoundedCornerShape(4.dp)))
                        }
                        Text(a.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(a.sub, fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
        SectionCard("Color theme") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Themes.all.forEach { t ->
                    val sel = p.themeId == t.id
                    Column(Modifier.width(96.dp).clip(RoundedCornerShape(14.dp)).background(Color(t.bg2))
                        .border(2.dp, if (sel) Color(t.accent) else Color.Transparent, RoundedCornerShape(14.dp)).clickable { vm.setTheme(t.id) }.padding(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(20.dp).background(Color(t.accent), CircleShape))
                            Box(Modifier.size(20.dp).background(Color(t.accent2), CircleShape))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        SectionCard("Editing speed") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pacing.entries.forEach { pc ->
                    FilterChip(selected = p.pacing == pc, onClick = { vm.setPacing(pc) }, label = { Text(pc.label) }, modifier = Modifier.weight(1f))
                }
            }
            Text(when (p.pacing) {
                Pacing.CALM -> "Longer scenes, smooth fades — great for storytelling"
                Pacing.BALANCED -> "New visual every 3-5 sec — best for most videos"
                Pacing.ENERGETIC -> "Fast cuts, glitch & zoom punches — shorts / high retention"
            }, color = Muted, fontSize = 12.sp)
        }
        SectionCard("Extras") {
            Text("Captions", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                com.gk.facelessstudio.model.CaptionMode.entries.forEach { m ->
                    FilterChip(selected = p.captionMode == m, onClick = { vm.setCaptionMode(m) }, label = { Text(m.label, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                }
            }
            Text(p.captionMode.sub, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            ToggleRow("Auto sound effects (whoosh, pop, boom)", p.sfx, vm::setSfx)
            ToggleRow("Cinematic film grain", p.grain, vm::setGrain)
            ToggleRow("Progress bar", p.progressBar, vm::setProgressBar)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(p.handle, vm::setHandle, label = { Text("Channel handle (watermark + subscribe outro)") }, placeholder = { Text("@mychannel") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}




/** Story looks with a live preview rendered from the project's own first picture. */
@Composable
private fun StoryStylePicker(vm: AppViewModel) {
    val p by vm.project.collectAsState()
    val img = p.images.indexOfFirst { it.isNotBlank() }
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(com.gk.facelessstudio.model.StoryStyle.entries.size) { i ->
            val st = com.gk.facelessstudio.model.StoryStyle.entries[i]
            val sel = p.storyStyle == st
            val preview by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, st, img, p.images.getOrNull(img), p.aspect) {
                value = if (img < 0) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    runCatching { stylePreview(p, st, img, vm.fonts) }.getOrNull()
                }
            }
            Column(Modifier.width(170.dp).clip(RoundedCornerShape(14.dp)).background(Surface2)
                .border(2.dp, if (sel) Neon else Color.Transparent, RoundedCornerShape(14.dp)).clickable { vm.setStoryStyle(st) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
                    preview?.let { androidx.compose.foundation.Image(it, null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        ?: Text(st.emoji, fontSize = 34.sp)
                }
                Column(Modifier.padding(10.dp)) {
                    Text("${st.emoji} ${st.label}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(st.sub, color = Muted, fontSize = 11.sp, maxLines = 2)
                }
            }
        }
    }
    if (img < 0) Text("Add images in step 3 to see each style with your own pictures.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
}

private fun stylePreview(p: com.gk.facelessstudio.model.Project, st: com.gk.facelessstudio.model.StoryStyle, img: Int, fonts: com.gk.facelessstudio.render.Fonts): androidx.compose.ui.graphics.ImageBitmap {
    val (w, h) = 480 to 270
    val text = com.gk.facelessstudio.engine.GridWorkflow.pieces(p).getOrNull(img)?.ifBlank { null } ?: p.title.ifBlank { "Once upon a time" }
    val names = com.gk.facelessstudio.engine.ScriptAnalyzer.storyNames(p.script, p.characters)
    val scene = com.gk.facelessstudio.model.Scene(
        text = text, startMs = 0, endMs = 4000, type = com.gk.facelessstudio.model.SceneType.KEN_BURNS,
        transition = com.gk.facelessstudio.model.Transition.CUT, imageIndex = img, variant = 4242,
        title = com.gk.facelessstudio.engine.ScriptAnalyzer.phrase(text, 10),
        emphasis = com.gk.facelessstudio.engine.ScriptAnalyzer.storyEmphasis(text, names).ifEmpty { names.take(1).map { n -> n.replaceFirstChar { it.uppercase() } } },
    )
    val proj = p.copy(storyMode = true, storyStyle = st, scenes = listOf(scene), durationMs = 4000, aspect = com.gk.facelessstudio.model.Aspect.LANDSCAPE, progressBar = false, handle = "")
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    com.gk.facelessstudio.render.FrameRenderer(w, h, proj, fonts, com.gk.facelessstudio.render.ImageStore(p.images, 640)).draw(android.graphics.Canvas(bmp), 3200)
    return bmp.asImageBitmap()
}
