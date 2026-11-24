package com.gk.facelessstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.gk.facelessstudio.engine.GridWorkflow

private fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens the ChatGPT / Gemini app if installed, otherwise the website. */
private fun openAi(ctx: Context, pkg: String, url: String) {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagesStep(vm: AppViewModel) {
    val p by vm.project.collectAsState()
    val selected by vm.selectedGrid.collectAsState()
    val ctx = LocalContext.current
    val pieces = remember(p.script, p.pacing) { GridWorkflow.pieces(p) }
    LaunchedEffect(pieces) { vm.syncPanels() }
    val grids = remember(pieces.size, p.gridSize) { GridWorkflow.grids(pieces.size, p.gridSize) }
    val usingOwn = !p.lineImages && p.images.isNotEmpty()
    val missing = if (usingOwn) 0 else pieces.indices.count { p.images.getOrNull(it).isNullOrBlank() }
    val gridsDone = grids.count { g -> (g.from until g.to).all { !p.images.getOrNull(it).isNullOrBlank() } }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (selected < 0) vm.selectGrid(vm.nextEmptyGrid()) }

    if (pieces.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Write your script in step 1 first. Every line becomes a scene that needs a picture.", color = Muted)
        }
        return
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("${pieces.size} scenes", Neon)
                if (!usingOwn) Stat("Grids $gridsDone/${grids.size}", if (gridsDone == grids.size) Neon else Color(0xFFFFB020))
                if (!usingOwn) Stat(if (missing == 0) "All images ready" else "$missing images missing", if (missing == 0) Neon else Color(0xFFFFB020))
                if (usingOwn) Stat("Using my own ${p.images.size} images", Violet)
            }
        }
        item { StyleCard(vm) }
        item { DirectorCard(vm, pieces.size) }
        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Grid images", fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Stat("$gridsDone / ${grids.size} done", if (gridsDone == grids.size) Neon else Color(0xFFFFB020))
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { if (grids.isEmpty()) 0f else gridsDone / grids.size.toFloat() }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("1. Tap Copy next prompt and paste it into ChatGPT or Gemini.\n" +
                    "2. When the image is ready, tap Share → Faceless Studio (it goes into the selected yellow card), or long-press → Copy and tap Paste image.\n" +
                    "3. The app cuts it into its scenes and selects the next empty card. Repeat until all are done.",
                    color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("Quality tip: Gemini makes the sharpest grids. With 9 scenes per image each scene gets 1/9 of the picture — choose 4 per image for sharper scenes.",
                    color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                val next = grids.firstOrNull { g -> (g.from until g.to).any { p.images.getOrNull(it).isNullOrBlank() } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val g = next ?: return@Button
                        copyText(ctx, "prompt", vm.gridPrompt(g)); vm.selectGrid(g.index)
                        vm.toast("Prompt for image ${g.index + 1} copied ✅")
                    }, enabled = next != null, shape = RoundedCornerShape(12.dp)) {
                        Text(if (next != null) "Copy next prompt (Image ${next.index + 1} of ${grids.size})" else "All images done ✅", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = { openAi(ctx, "com.openai.chatgpt", "https://chatgpt.com/") }) { Text("Open ChatGPT") }
                    OutlinedButton(onClick = { openAi(ctx, "com.google.android.apps.bard", "https://gemini.google.com/app") }) { Text("Open Gemini") }
                    OutlinedButton(onClick = { copyText(ctx, "prompts", vm.allGridPrompts()); vm.toast("All ${grids.size} prompts copied ✅") }) { Text("Copy all prompts") }
                    OutlinedButton(onClick = {
                        val f = vm.writeShareFile("${p.title.ifBlank { "image" }.replace(Regex("[^A-Za-z0-9]"), "_")}_prompts.txt", vm.allGridPrompts())
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Prompts"))
                    }) { Text("Prompts .txt") }
                }
                Text("${pieces.size} scenes → ${grids.size} images (up to ${p.gridSize} scenes each)", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(grids, key = { "g${it.index}-${it.from}" }) { g -> GridCard(vm, g, grids.size, selected == g.index) }
        item { OwnImagesCard(vm, usingOwn) }
    }
}

@Composable
private fun Stat(text: String, color: Color) {
    Box(Modifier.border(1.5.dp, color, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun Card(highlight: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Surface1)
        .border(if (highlight) 3.dp else 1.dp, if (highlight) Color(0xFFFFC21A) else Color(0xFF243042), RoundedCornerShape(18.dp)).padding(16.dp)) {
        content()
    }
}

@Composable
private fun <T> Picker(label: String, value: String, options: List<Pair<T, String>>, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).clickable { open = true }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(value, modifier = Modifier.weight(1f), fontSize = 14.sp)
                Icon(Icons.Default.ExpandMore, null, tint = Muted)
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onPick(v) }) }
            }
        }
    }
}

@Composable
private fun StyleCard(vm: AppViewModel) {
    val p by vm.project.collectAsState()
    Card {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Picker("Art style", GridWorkflow.preset(p.artStyle).label, GridWorkflow.STYLES.map { it.id to it.label }, vm::setArtStyle, Modifier.weight(1f))
            Picker("Line between panels", mapOf("auto" to "Auto (style)", "white" to "White", "black" to "Black", "none" to "None")[p.separator] ?: "Auto",
                listOf("auto" to "Auto (based on style)", "white" to "White line", "black" to "Black line", "none" to "No line"), vm::setSeparator, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Picker("Scenes per image", "${p.gridSize} " + when (p.gridSize) { 4 -> "(2×2, sharpest)"; 6 -> "(3×2)"; else -> "(3×3, fewest images)" },
            listOf(4 to "4 per image (2×2, sharpest)", 6 to "6 per image (3×2)", 9 to "9 per image (3×3, fewest images)"), vm::setGridSize)
        Spacer(Modifier.height(12.dp))
        Text("Style description (goes into the prompt)", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(GridWorkflow.styleText(p), vm::setStyleDesc, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp))
        Spacer(Modifier.height(12.dp))
        Text("Characters (one per line, Name: look) — kept the same in every image", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(p.characters, vm::setCharacters, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = { Text("Karna: tall young warrior, golden armour and earrings, long black hair, calm proud face\nKrishna: blue skin, peacock feather crown, yellow silk dhoti, gentle smile") },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectorCard(vm: AppViewModel, total: Int) {
    val p by vm.project.collectAsState()
    val ctx = LocalContext.current
    var reply by remember { mutableStateOf("") }
    val have = (0 until total).count { !p.visuals.getOrNull(it).isNullOrBlank() }
    val per = p.directorPart
    val parts = (total + per - 1) / per
    Card {
        Text("AI Scene Director (optional, best result)", fontWeight = FontWeight.Black, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("Want a good visual description for every scene? Copy a part's prompt into ChatGPT/Gemini, paste the reply below and press Apply. " +
            "Every scene's visual is then used in the image prompts. Long scripts are split into parts (AI chats stop writing after ~200 lines).",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scenes per part", modifier = Modifier.weight(1f), color = Muted)
            Picker("", "$per", listOf(50 to "50", 100 to "100", 150 to "150", 200 to "200"), vm::setDirectorPart, Modifier.width(110.dp))
        }
        Spacer(Modifier.height(8.dp))
        Stat("$have / $total scenes have visuals", if (have == total) Neon else Color(0xFFFFB020))
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (part in 0 until parts) {
                val from = part * per; val to = minOf(total, from + per)
                val done = (from until to).all { !p.visuals.getOrNull(it).isNullOrBlank() }
                OutlinedButton(onClick = { copyText(ctx, "director", vm.directorPartPrompt(part)); vm.toast("Part ${part + 1} prompt copied ✅") }) {
                    Text("Part ${part + 1}: ${from + 1}–$to" + if (done) " ✓" else "")
                }
            }
            OutlinedButton(onClick = {
                val t = vm.directorMissingPrompt()
                if (t == null) vm.toast("Every scene already has a visual ✅") else { copyText(ctx, "director", t); vm.toast("Prompt for missing scenes copied ✅") }
            }) { Text("Copy prompt for scenes still missing") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(reply, { reply = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            placeholder = { Text("1: Karna stands alone at the river bank at sunrise, wide shot\n2: close-up of Krishna's calm smile…") },
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.applyDirector(reply); reply = "" }, enabled = reply.isNotBlank(), shape = RoundedCornerShape(12.dp)) { Text("Apply", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { reply = it }
            }) { Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste reply") }
        }
    }
}

@Composable
private fun GridCard(vm: AppViewModel, g: GridWorkflow.Grid, total: Int, selected: Boolean) {
    val p by vm.project.collectAsState()
    val ctx = LocalContext.current
    var showPrompt by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.importGrid(g.index, u) } }
    val done = (g.from until g.to).all { !p.images.getOrNull(it).isNullOrBlank() }
    Card(highlight = selected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Image ${g.index + 1}", fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(" of $total", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Stat("Scenes ${g.from + 1}–${g.to} · ${g.cols}×${g.rows}", Muted)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                done -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Neon); Spacer(Modifier.width(4.dp)); Text("Done", color = Neon, fontWeight = FontWeight.Bold) }
                selected -> Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFFFC21A)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Selected · Paste goes here", color = Color.Black, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                else -> Stat("Pending", Color(0xFFFFB020))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { copyText(ctx, "prompt", vm.gridPrompt(g)); vm.selectGrid(g.index); vm.toast("Prompt for image ${g.index + 1} copied ✅") },
                shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy prompt") }
        }
        Row(Modifier.fillMaxWidth().clickable { showPrompt = !showPrompt }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (showPrompt) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted)
            Text("Prompt text", color = Muted)
        }
        if (showPrompt) Text(vm.gridPrompt(g), fontSize = 12.sp, color = Color(0xFFB8C4D4), modifier = Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(10.dp)).padding(10.dp))
        if (done) {
            // cut panels preview, in order
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (g.from until g.to).take(9).forEach { i ->
                    val th = rememberThumb(p.images.getOrNull(i), 160)
                    Box(Modifier.weight(1f).aspectRatio(p.aspect.ratioW / p.aspect.ratioH.toFloat()).clip(RoundedCornerShape(4.dp)).background(Surface2)) {
                        th?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { vm.selectGrid(g.index); vm.pasteGrid(g.index) }) { Text("Replace with pasted image") }
                TextButton(onClick = { vm.selectGrid(g.index); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose file…") }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp))
                .border(BorderStroke(2.dp, if (selected) Color(0xFFFFC21A) else Color(0xFF3A4658)), RoundedCornerShape(14.dp))
                .clickable { vm.selectGrid(g.index) }.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (selected) "Selected. Now share or paste the image" else "Tap here to select this card", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.selectGrid(g.index); vm.pasteGrid(g.index) }, shape = RoundedCornerShape(12.dp)) { Text("Paste image") }
                    OutlinedButton(onClick = { vm.selectGrid(g.index); picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose file…")
                    }
                }
            }
        }
    }
}

@Composable
private fun OwnImagesCard(vm: AppViewModel, usingOwn: Boolean) {
    var open by remember { mutableStateOf(usingOwn) }
    val p by vm.project.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addImages(it) }
    var selected by remember { mutableIntStateOf(-1) }
    Card {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("Or use my own images", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted)
        }
        if (open) {
            Text("Add ready-made pictures instead of grids. They are spread across the video in order.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(8.dp)); Text("Add images")
            }
            if (usingOwn) {
                Spacer(Modifier.height(10.dp))
                // simple wrap grid of thumbnails (no nested lazy list)
                p.images.chunked(4).forEachIndexed { row, chunk ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEachIndexed { j, path ->
                            val i = row * 4 + j
                            val th = rememberThumb(path, 200)
                            Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Surface2)
                                .border(2.dp, if (selected == i) Neon else Color.Transparent, RoundedCornerShape(8.dp)).clickable { selected = if (selected == i) -1 else i }) {
                                th?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(4.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp))
                            }
                        }
                        repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (selected >= 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.moveImage(selected, -1); selected = (selected - 1).coerceAtLeast(0) }) { Text("◀ Move") }
                    TextButton(onClick = { vm.moveImage(selected, 1); selected = (selected + 1).coerceAtMost(p.images.lastIndex) }) { Text("Move ▶") }
                    TextButton(onClick = { vm.removeImage(selected); selected = -1 }) { Text("Remove", color = Hot) }
                }
            }
        }
    }
}
