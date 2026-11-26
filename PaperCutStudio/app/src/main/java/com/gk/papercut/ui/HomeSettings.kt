package com.gk.papercut.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gk.papercut.data.Aspect
import com.gk.papercut.ml.OfflineModel
import java.text.DateFormat
import java.util.Date

@Composable
fun ToastHost(vm: StudioViewModel) {
    val msg by vm.toast.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.toastShown() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: StudioViewModel, onSettings: () -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<String?>(null) }
    ToastHost(vm)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PaperCut Studio", fontWeight = FontWeight.Bold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New video") })
        },
    ) { pad ->
        if (projects.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Make paper cut-out videos", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("\n1. Paste your script and add your voice-over\n2. Copy the image prompts and create the images in any AI tool\n3. Import the images – the app cuts out every element offline\n4. Preview the synced animation and export an MP4", fontSize = 15.sp)
            }
        } else {
            LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp)) {
                items(projects, key = { it.id }) { p ->
                    SectionCard(modifier = Modifier.clickable { vm.open(p.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Hint("${p.aspect.label} · ${p.scenes.size} scenes · ${fmtTime(p.audioDuration)} · ${DateFormat.getDateInstance().format(Date(p.updatedAt))}")
                            }
                            IconButton(onClick = { toDelete = p.id }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        var aspect by remember { mutableStateOf(Aspect.LANDSCAPE) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New video") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Video name") }, singleLine = true)
                    Aspect.entries.forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable { aspect = a }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(aspect == a, { aspect = a }); Text(a.label)
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { creating = false; vm.createProject(name, aspect) }) { Text("Create") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
    toDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this project?") },
            text = { Text("Images, layers and the exported video inside the app will be removed.") },
            confirmButton = { Button(onClick = { vm.deleteProject(id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val progress by vm.modelProgress.collectAsStateWithLifecycle()
    val version by vm.modelsVersion.collectAsStateWithLifecycle()
    ToastHost(vm)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            SectionCard("Offline AI models") {
                Hint("Download once (Wi-Fi recommended). After that, element separation runs fully offline on your phone. ML Kit's subject model is installed automatically by Google Play services.")
                OfflineModel.entries.forEach { m ->
                    val ready = remember(version, progress) { vm.models.isReady(m) }
                    val p = progress[m]
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, fontWeight = FontWeight.SemiBold)
                                Hint(
                                    when {
                                        p != null -> "Downloading ${(p * 100).toInt()}%"
                                        ready -> "Ready ✓"
                                        else -> "~${m.approxMb} MB"
                                    }
                                )
                            }
                            if (ready && p == null) OutlinedButton(onClick = { vm.deleteModel(m) }) { Text("Delete") }
                            else if (p == null) Button(onClick = { vm.downloadModel(m) }) { Text("Download") }
                        }
                        if (p != null) LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                    }
                }
                Hint("MobileSAM: finds extra elements and enables tap-to-cut.\nLaMa: repaints the background behind cut-outs (large, needs a phone with 6 GB+ RAM). Without LaMa a quick built-in fill is used.")
            }
            SectionCard("AI Director (optional, online)") {
                var key by remember { mutableStateOf(vm.settings.geminiKey) }
                var model by remember { mutableStateOf(vm.settings.textModel) }
                Hint("With a free Gemini API key (aistudio.google.com) the app writes richer English image prompts and picks animations for you. Without a key, prompts are written offline.")
                OutlinedTextField(key, { key = it; vm.settings.geminiKey = it }, label = { Text("Gemini API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it; vm.settings.textModel = it }, label = { Text("Text model") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
