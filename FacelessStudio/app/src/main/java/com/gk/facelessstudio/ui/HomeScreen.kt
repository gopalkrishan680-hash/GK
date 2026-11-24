package com.gk.facelessstudio.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.facelessstudio.export.ExportManager
import com.gk.facelessstudio.export.ExportState
import com.gk.facelessstudio.model.Project

@Composable
fun HomeScreen(vm: AppViewModel, onNew: () -> Unit, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsState()
    val export by ExportManager.flow.collectAsState()

    Scaffold(containerColor = Bg) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(Modifier.statusBarsPadding()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(BrandGradient, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Movie, null, tint = Color.Black)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Faceless Studio", fontSize = 24.sp, fontWeight = FontWeight.Black)
                            Text("Script + Voice + Images = Video", color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            item { NewVideoCard(onNew) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Step(Icons.AutoMirrored.Filled.Notes, "1", "Script", Modifier.weight(1f))
                    Step(Icons.Default.Mic, "2", "Voiceover", Modifier.weight(1f))
                    Step(Icons.Default.Image, "3", "Images", Modifier.weight(1f))
                    Step(Icons.Default.AutoAwesome, "4", "Auto Edit", Modifier.weight(1f))
                }
            }
            (export as? ExportState.Running)?.let { r ->
                item {
                    Column(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text("Rendering video… ${(r.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { r.progress }, Modifier.fillMaxWidth())
                        Text(r.message, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (projects.isNotEmpty()) item { Text("My Projects", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            items(projects, key = { it.id }) { p ->
                ProjectCard(p, onClick = { onOpen(p.id) }, onDelete = { vm.delete(p.id) }, onDuplicate = { vm.duplicate(p) })
            }
            if (projects.isEmpty()) item {
                Text("No projects yet. Tap \"Create New Video\" above 👆", color = Muted, modifier = Modifier.padding(top = 20.dp))
            }
        }
    }
}

@Composable
private fun NewVideoCard(onNew: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(BrandGradient).clickable(onClick = onNew).padding(22.dp)
    ) {
        Column {
            Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.85f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, tint = Neon, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Create New Video", color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Text("Just add a script, voiceover and images — the app adds animations, captions, transitions, music and sound effects for you.",
                color = Color.Black.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun Step(icon: ImageVector, n: String, label: String, modifier: Modifier) {
    Column(modifier.background(Surface1, RoundedCornerShape(14.dp)).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Neon)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProjectCard(p: Project, onClick: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit) {
    val thumb = rememberThumb(p.images.firstOrNull { it.isNotBlank() })
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 96.dp, height = 56.dp).clip(RoundedCornerShape(10.dp)).background(Surface2), contentAlignment = Alignment.Center) {
            if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.Movie, null, tint = Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title.ifBlank { p.script.take(40).ifBlank { "Untitled" } }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.durationMs > 0) Pill(fmtTime(p.durationMs))
                if (p.scenes.isNotEmpty()) Pill("${p.scenes.size} scenes")
                if (p.lastExport != null) Pill("Exported", Neon.copy(alpha = 0.2f), Neon)
                if (p.scenes.isEmpty()) Pill("Draft", Hot.copy(alpha = 0.2f), Hot)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
