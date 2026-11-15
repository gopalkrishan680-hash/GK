package com.kahani.studio3d.ui

import android.media.MediaPlayer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.AspectRatio
import com.kahani.studio3d.data.Character
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.SplitMode
import com.kahani.studio3d.data.VisualStyle
import com.kahani.studio3d.logic.SceneLogic
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScriptStep(vm: StudioViewModel, p: Project) {
    var text by remember(p.id) { mutableStateOf(p.script) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(text) {
        if (text != p.script) { delay(700); vm.applyScript(text) }
    }
    val latest = androidx.compose.runtime.rememberUpdatedState(text)
    DisposableEffect(p.id) {
        onDispose { if (latest.value != vm.current.value?.script) vm.applyScript(latest.value) }
    }
    val pickVoice = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.importVoice(it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("1. Script", "Any language — English, Hindi, Hinglish…") {
            OutlinedTextField(
                text, { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                placeholder = { Text("Once upon a time, in a small village, lived a boy named Raju…") },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val words = SceneLogic.wordCount(text)
                Text("$words words • ~${fmtTime((words / 2.4 * 1000).toLong())} narration • ${p.scenes.size} scenes",
                    color = K.TextDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SoftButton("Paste", Icons.Default.ContentPaste) { clipboard.getText()?.text?.let { text = it } }
            }
            Spacer(Modifier.height(10.dp))
            Text("How to split scenes?", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitMode.entries.forEach { m ->
                    Pill(m.label, p.splitMode == m) { vm.update { it.copy(splitMode = m) }; vm.applyScript(text) }
                }
            }
        }

        SectionCard("2. Voiceover", "MP3 / M4A / WAV / OGG. Scenes auto-sync to the pauses in your voice.") {
            if (p.voicePath == null) {
                GradientButton("Upload voiceover", Modifier.fillMaxWidth(), icon = Icons.Default.UploadFile) { pickVoice.launch("audio/*") }
            } else {
                VoiceRow(p) { pickVoice.launch("audio/*") }
            }
        }

        SectionCard("3. 3D Style") {
            val rows = VisualStyle.entries.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        r.forEach { st -> Box(Modifier.weight(1f)) { StyleTile(st, p.style == st) { vm.update { it.copy(style = st) } } } }
                        if (r.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Video size", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AspectRatio.entries.forEach { a -> Pill(a.label, p.aspect == a) { vm.update { it.copy(aspect = a) } } }
            }
        }

        CharactersCard(vm, p)
        AdvancedPromptCard(vm, p)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun VoiceRow(p: Project, onReplace: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    val player = remember(p.voicePath) { MediaPlayer().apply { runCatching { setDataSource(p.voicePath); prepare() } } }
    DisposableEffect(player) { onDispose { player.release() } }
    player.setOnCompletionListener { playing = false }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(K.Grad), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.voiceName ?: "voice", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("Length ${fmtTime(p.voiceDurationMs)} • auto-sync ON", color = K.Green, fontSize = 12.sp)
        }
        IconButton(onClick = {
            if (playing) player.pause() else player.start(); playing = !playing
        }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) }
        SoftButton("Change") { onReplace() }
    }
}

@Composable
private fun CharactersCard(vm: StudioViewModel, p: Project) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    SectionCard("Character Bible", "Keep faces and outfits consistent. Whenever a name appears in the script, its description is added to that prompt.") {
        p.characters.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(K.Surface2).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = K.Cyan)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(c.description, color = K.TextDim, fontSize = 12.sp)
                }
                IconButton(onClick = { vm.setCharacters(p.characters.toMutableList().apply { removeAt(i) }) }) {
                    Icon(Icons.Default.Close, null, tint = K.TextDim)
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Raju)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(desc, { desc = it }, label = { Text("Look: 8-year-old boy, curly hair, yellow t-shirt…") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SoftButton("Add character", Icons.Default.Add) {
            if (name.isNotBlank() && desc.isNotBlank()) {
                vm.setCharacters(p.characters + Character(name.trim(), desc.trim())); name = ""; desc = ""
            }
        }
    }
}

@Composable
private fun AdvancedPromptCard(vm: StudioViewModel, p: Project) {
    var open by remember { mutableStateOf(false) }
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced prompt settings", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Color.White) }
        }
        if (open) {
            OutlinedTextField(p.extraPrompt, { v -> vm.update { it.copy(extraPrompt = v) } },
                label = { Text("Add to every prompt (e.g. Indian village, 1990s)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(p.negativePrompt, { v -> vm.update { it.copy(negativePrompt = v) } },
                label = { Text("Negative prompt") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
