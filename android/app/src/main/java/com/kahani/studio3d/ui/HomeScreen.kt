package com.kahani.studio3d.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kahani.studio3d.StudioViewModel
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.VisualStyle
import java.io.File

val styleColors = mapOf(
    VisualStyle.PIXAR_3D to listOf(Color(0xFF4FC3F7), Color(0xFFFF8A65)),
    VisualStyle.REALISTIC_3D to listOf(Color(0xFF263238), Color(0xFFFFB300)),
    VisualStyle.CLAY_3D to listOf(Color(0xFFFFAB91), Color(0xFFCE93D8)),
    VisualStyle.ANIME_3D to listOf(Color(0xFFF06292), Color(0xFF7986CB)),
    VisualStyle.MINIATURE to listOf(Color(0xFF81C784), Color(0xFFFFF176)),
    VisualStyle.LOW_POLY to listOf(Color(0xFF4DB6AC), Color(0xFF9575CD)),
    VisualStyle.MYTHOLOGY to listOf(Color(0xFFFF6F00), Color(0xFFFFD54F)),
    VisualStyle.HORROR to listOf(Color(0xFF1B1B2F), Color(0xFF7B1F2E)),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: StudioViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val projects by vm.projects.collectAsState()
    var askTitle by remember { mutableStateOf<VisualStyle?>(null) }
    var showNew by remember { mutableStateOf(false) }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(K.Grad), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Movie, null, tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Kahani 3D Studio", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Script + Voice → 3D Video", fontSize = 12.sp, color = K.TextDim)
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = Color.White) }
            }
        }
        item(span = { GridItemSpan(2) }) { HeroCard { showNew = true } }
        item(span = { GridItemSpan(2) }) {
            Column {
                Text("Pick a style to start", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(VisualStyle.entries) { st -> StyleTile(st, selected = false, width = 120) { askTitle = st } }
                }
            }
        }
        item(span = { GridItemSpan(2) }) {
            Text(
                if (projects.isEmpty()) "No projects yet — create one above ✨" else "My Projects (${projects.size})",
                fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 6.dp)
            )
        }
        items(projects, key = { it.id }) { p ->
            ProjectCard(p, onClick = { onOpen(p.id) }, onDelete = { vm.delete(p.id) }, onDuplicate = { vm.duplicate(p) })
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(60.dp)) }
    }

    if (showNew || askTitle != null) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNew = false; askTitle = null },
            title = { Text("New Video") },
            text = {
                OutlinedTextField(title, { title = it }, label = { Text("Video name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = vm.newProject(title)
                    askTitle?.let { st -> vm.update { it.copy(style = st) } }
                    showNew = false; askTitle = null
                    onOpen(id)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false; askTitle = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HeroCard(onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(K.GradSoft)
            .border(1.dp, K.Stroke, RoundedCornerShape(22.dp)).clickable(onClick = onClick).padding(18.dp)
    ) {
        Text("Create a new 3D video", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Text("Add a script and voiceover — the app does the rest", fontSize = 13.sp, color = K.TextDim)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Step(Icons.Default.TextSnippet, "Script")
            Step(Icons.Default.Mic, "Voice")
            Step(Icons.Default.AutoAwesome, "Prompts")
            Step(Icons.Default.Image, "Images")
            Step(Icons.Default.Movie, "Video")
        }
        Spacer(Modifier.height(14.dp))
        GradientButton("Create New Project", Modifier.fillMaxWidth(), icon = Icons.Default.Add, onClick = onClick)
    }
}

@Composable
private fun Step(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Text(label, fontSize = 11.sp, color = Color.White, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun StyleTile(st: VisualStyle, selected: Boolean, width: Int? = null, onClick: () -> Unit) {
    val cols = styleColors[st] ?: listOf(K.Violet, K.Pink)
    val m = if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth()
    Box(
        m.height(78.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(cols))
            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(st.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun ProjectCard(p: Project, onClick: () -> Unit, onDelete: () -> Unit, onDuplicate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(K.Surface).border(1.dp, K.Stroke, RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.85f).background(Brush.linearGradient(styleColors[p.style] ?: listOf(K.Violet, K.Pink)))) {
            p.thumbnail?.let { AsyncImage(model = File(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; confirm = true })
                }
            }
            Text(
                fmtTime(maxOf(p.totalDurationMs, p.voiceDurationMs)),
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Column(Modifier.padding(10.dp)) {
            Text(p.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.scenes.size} scenes • ${p.style.label}", color = K.TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.scenes.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { p.imagesDone.toFloat() / p.scenes.size },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp)),
                    color = K.Green, trackColor = K.Stroke
                )
                Text("Images ${p.imagesDone}/${p.scenes.size}", color = K.TextDim, fontSize = 10.sp)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete project?") },
        text = { Text("\"${p.title}\" will be deleted permanently.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete", color = K.Pink) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}
