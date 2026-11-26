package com.gk.papercut.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
fun <T> Picker(label: String, value: T, options: List<T>, name: (T) -> String, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: ${name(value)}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(name(o)) }, onClick = { onPick(o); open = false }) }
        }
    }
}

@Composable
fun BusyBar(busy: Busy?, onCancel: (() -> Unit)? = null) {
    if (busy == null) return
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(busy.message, Modifier.weight(1f), fontSize = 14.sp)
                if (onCancel != null) androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
            }
            if (busy.progress != null) LinearProgressIndicator(progress = { busy.progress }, Modifier.fillMaxWidth().padding(top = 6.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

/** Loads a project image off the main thread. */
@Composable
fun rememberProjectBitmap(vm: StudioViewModel, projectId: String, file: String?): Bitmap? {
    val b by produceState<Bitmap?>(null, projectId, file) {
        value = if (file == null) null else withContext(Dispatchers.IO) { vm.repo.loadBitmap(projectId, file) }
    }
    return b
}

@Composable
fun Hint(text: String) = Text(text, fontSize = 13.sp, color = Color(0xFF6B5B4A))

@Composable
fun EmptyState(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = Color(0xFF6B5B4A))
}

fun fmtTime(s: Double): String {
    val t = s.coerceAtLeast(0.0)
    return "%d:%04.1f".format((t / 60).toInt(), t % 60)
}
