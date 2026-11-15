package com.kahani.studio3d.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kahani.studio3d.StudioViewModel

private val steps = listOf("Script", "Prompts", "Images", "Edit", "Export")

@Composable
fun ProjectScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val p by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf(false) }
    val project = p ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    BackHandler { if (step > 0) step-- else onBack() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Text(project.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { rename = true }) { Icon(Icons.Default.Edit, null, tint = K.TextDim) }
        }
        StepBar(step, done = listOf(
            project.scenes.isNotEmpty() && project.voicePath != null,
            project.scenes.isNotEmpty() && project.scenes.all { it.prompt.isNotBlank() },
            project.scenes.isNotEmpty() && project.imagesDone == project.scenes.size,
            false, false
        )) { step = it }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> ScriptStep(vm, project)
                1 -> PromptsStep(vm, project)
                2 -> ImagesStep(vm, project)
                3 -> EditStep(vm, project)
                else -> ExportStep(vm, project)
            }
        }
        if (step < 4) {
            Row(Modifier.fillMaxWidth().background(K.Bg).navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) SoftButton("Back", Icons.AutoMirrored.Filled.ArrowBack, Modifier.weight(0.4f)) { step-- }
                val nextLabel = when (step) {
                    0 -> "Create prompts"
                    1 -> "Upload images"
                    2 -> "Edit & Preview"
                    else -> "Export Video"
                }
                GradientButton(nextLabel, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = project.scenes.isNotEmpty() || (step == 0 && project.script.isNotBlank())) {
                    step++
                }
            }
        }
    }

    busy?.let { b ->
        Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
            Column(Modifier.padding(32.dp).clip(RoundedCornerShape(18.dp)).background(K.Surface).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = K.Pink)
                Spacer(Modifier.height(14.dp))
                Text(b.label, color = Color.White)
                b.progress?.let {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.width(200.dp), color = K.Violet, trackColor = K.Stroke)
                }
            }
        }
    }

    }

    if (rename) {
        var t by remember { mutableStateOf(project.title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.update { it.copy(title = t.ifBlank { it.title }) }; rename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun StepBar(current: Int, done: List<Boolean>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, label ->
            val active = i == current
            Column(Modifier.weight(1f).clickable { onSelect(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(50))
                        .background(
                            when {
                                active -> K.Grad
                                done[i] -> Brush.linearGradient(listOf(K.Green, K.Green))
                                else -> Brush.linearGradient(listOf(K.Surface2, K.Surface2))
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (done[i] && !active) Icon(Icons.Default.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    else Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(label, color = if (active) Color.White else K.TextDim, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
            if (i < steps.lastIndex) Box(Modifier.weight(0.3f).height(2.dp).background(if (i < current) K.Violet else K.Stroke))
        }
    }
}
