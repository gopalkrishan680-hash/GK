package com.gk.smartcompressor.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.smartcompressor.App
import com.gk.smartcompressor.MainActivity
import com.gk.smartcompressor.core.FileKind
import com.gk.smartcompressor.core.Fmt
import com.gk.smartcompressor.core.HistoryItem
import com.gk.smartcompressor.core.ImageFormat
import com.gk.smartcompressor.core.Job
import com.gk.smartcompressor.core.JobState
import com.gk.smartcompressor.core.Level
import com.gk.smartcompressor.core.Options
import com.gk.smartcompressor.core.PdfMode
import com.gk.smartcompressor.core.VideoRes
import java.text.DateFormat
import java.util.Date

private val Green = Color(0xFF16A34A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Smart Compressor", fontWeight = FontWeight.Bold)
                    Text("100% offline • private • free", style = MaterialTheme.typography.labelSmall)
                }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Home, null) }, label = { Text("Compress") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.List, null) }, label = { Text("History") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) CompressTab(activity) else HistoryTab()
        }
    }
}

// ───────────────────────────── Compress tab ─────────────────────────────

@Composable
private fun CompressTab(activity: MainActivity) {
    val app = App.instance
    val jobs by app.jobs.jobs.collectAsState()
    val history by app.history.items.collectAsState()
    val options by app.settings.options.collectAsState()
    val ctx = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        activity.addUris(uris)
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { SavedBanner(history) }
        item {
            SectionTitle("1. File chuno")
            PickGrid { mimes -> picker.launch(mimes) }
        }
        if (activity.staged.isNotEmpty()) {
            item {
                StagedCard(activity, options) {
                    app.jobs.enqueue(activity.staged.toList(), options)
                    activity.staged.clear()
                    Toast.makeText(ctx, "Compression shuru! Notification me progress dikhega", Toast.LENGTH_SHORT).show()
                }
            }
        }
        item {
            SectionTitle("2. Settings")
            OptionsCard(options) { app.settings.update(it) }
        }
        if (jobs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("3. Progress", Modifier.weight(1f))
                    if (app.jobs.isBusy()) TextButton({ app.jobs.cancelAll() }) { Text("Sab roko") }
                    TextButton({ app.jobs.clearFinished() }) { Text("Saaf karo") }
                }
            }
            items(jobs.reversed(), key = { it.id }) { JobCard(it) }
        }
        item { TipsCard() }
    }
}

@Composable
private fun SectionTitle(t: String, modifier: Modifier = Modifier) =
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 8.dp))

@Composable
private fun SavedBanner(history: List<HistoryItem>) {
    val saved = history.sumOf { (it.origSize - it.newSize).coerceAtLeast(0) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("💾", fontSize = 36.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Ab tak bachaya", style = MaterialTheme.typography.labelLarge)
                Text(Fmt.size(saved), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${history.size} files compress ki gayi", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class PickType(val emoji: String, val label: String, val mimes: Array<String>)

private val pickTypes = listOf(
    PickType("🎬", "Video", arrayOf("video/*")),
    PickType("🖼️", "Photo", arrayOf("image/*")),
    PickType("📄", "PDF", arrayOf("application/pdf")),
    PickType("🎵", "Audio", arrayOf("audio/*")),
    PickType("📦", "Koi bhi", arrayOf("*/*")),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickGrid(onPick: (Array<String>) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        maxItemsInEachRow = 3,
        modifier = Modifier.fillMaxWidth()
    ) {
        pickTypes.forEach { t ->
            Card(
                modifier = Modifier.weight(1f).clickable { onPick(t.mimes) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(t.emoji, fontSize = 30.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
    Text("Tip: kisi bhi app (Gallery, WhatsApp, Files) se 'Share' → Smart Compressor bhi kar sakte ho. " +
        "Ek saath kai files chun sakte ho.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun StagedCard(activity: MainActivity, options: Options, onStart: () -> Unit) {
    val total = activity.staged.sumOf { it.second.size }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Chuni gayi files (${activity.staged.size}) • ${Fmt.size(total)}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            activity.staged.toList().forEach { (uri, info) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.kind.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Fmt.size(info.size), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ activity.staged.removeAll { it.first == uri } }) { Icon(Icons.Default.Close, "Hatao") }
                }
            }
            options.targetReduction?.let {
                Text("Andaazan output: ~${Fmt.size(total * (100 - it) / 100)} ($it% chhota)",
                    color = Green, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Button(onStart, Modifier.fillMaxWidth().height(52.dp)) {
                Text("⚡ Compress Karo", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsCard(o: Options, onChange: (Options) -> Unit) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Compression level", fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Level.entries.forEach { l ->
                    FilterChip(o.level == l, { onChange(o.copy(level = l)) }, { Text(l.label) })
                }
            }
            Text(o.level.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("🎯 Target size mode", fontWeight = FontWeight.SemiBold)
                    Text("Batao kitna % chhota chahiye – app khud setting adjust karega",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(o.targetReduction != null, { onChange(o.copy(targetReduction = if (it) 90 else null)) })
            }
            o.targetReduction?.let { t ->
                Text("$t% chhota karo", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Slider(
                    value = t.toFloat(), valueRange = 50f..97f, steps = 46,
                    onValueChange = { onChange(o.copy(targetReduction = it.toInt())) }
                )
                if (t >= 93) Text("⚠️ Itna zyada compression me quality thodi kam dikh sakti hai",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            Text(if (advanced) "▲ Advanced settings" else "▼ Advanced settings",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { advanced = !advanced }.padding(vertical = 4.dp))
            AnimatedVisibility(advanced) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text("🎬 Video resolution (max)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VideoRes.entries.forEach { r ->
                            FilterChip(o.videoRes == r, { onChange(o.copy(videoRes = r)) }, { Text(r.label) })
                        }
                    }
                    SwitchRow("Video se awaaz hatao (aur chhota)", o.removeAudio) { onChange(o.copy(removeAudio = it)) }
                    SwitchRow("H.264 compatibility (purane TV/PC ke liye)", o.h264Compat) { onChange(o.copy(h264Compat = it)) }
                    Text("H.265 (default) same quality me ~40% aur chhoti file banata hai.",
                        style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(10.dp))
                    Text("🖼️ Photo format", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ImageFormat.entries.forEach { f ->
                            FilterChip(o.imageFormat == f, { onChange(o.copy(imageFormat = f)) }, { Text(f.label) })
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("📄 PDF mode", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PdfMode.entries.forEach { m ->
                            FilterChip(o.pdfMode == m, { onChange(o.copy(pdfMode = m)) }, { Text(m.label) })
                        }
                    }
                    if (o.pdfMode == PdfMode.SCAN) Text("Scan mode me text select/search nahi hoga.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

@Composable
private fun JobCard(job: Job) {
    val ctx = LocalContext.current
    val app = App.instance
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(job.kind.emoji, fontSize = 26.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text("Original: ${Fmt.size(job.size)}", style = MaterialTheme.typography.bodySmall)
                }
                if (job.state is JobState.Queued || job.state is JobState.Running) {
                    IconButton({ app.jobs.cancel(job.id) }) { Icon(Icons.Default.Close, "Cancel") }
                }
            }
            Spacer(Modifier.height(8.dp))
            when (val s = job.state) {
                JobState.Queued -> Text("⏳ Line me intezaar…", style = MaterialTheme.typography.bodyMedium)
                is JobState.Running -> {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                    Spacer(Modifier.height(4.dp))
                    Row {
                        Text("${(s.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(s.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                is JobState.Done -> {
                    ResultRow(job.size, s.outSize)
                    Text("Saved: Download/SmartCompressor/${s.outName}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton({ openUri(ctx, s.outUri, s.mime) }) { Text("Kholo") }
                        Button({ shareUri(ctx, s.outUri, s.mime) }) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                    }
                }
                is JobState.AlreadySmall -> Text(
                    "✔️ Ye file pehle se hi compressed hai – aur chhoti karne se quality kharab hogi, isliye original hi best hai.",
                    style = MaterialTheme.typography.bodyMedium)
                is JobState.Failed -> Text("❌ ${s.message}", color = MaterialTheme.colorScheme.error)
                JobState.Cancelled -> Text("Cancel kar diya", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultRow(orig: Long, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${Fmt.size(orig)} → ${Fmt.size(now)}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Text("-${Fmt.saved(orig, now)}%", color = Green, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    }
}

@Composable
private fun TipsCard() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Text("💡 Sach baat (zaroor padhein)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "• Phone camera ki videos aur scanned PDF/photos me 85-95% tak bachat aam baat hai.\n" +
                "• Jo file pehle se compressed hai (WhatsApp video, chhota PDF, MP3) wo zyada chhoti nahi hogi – " +
                "app aisi file ko kharab nahi karta, 'pehle se compressed' bata deta hai.\n" +
                "• Original file kabhi delete nahi hoti. Nayi file Download/SmartCompressor me save hoti hai.\n" +
                "• Badi video me kuch minute lag sakte hain – app band kar do, background me chalta rahega.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ───────────────────────────── History tab ─────────────────────────────

@Composable
private fun HistoryTab() {
    val store = App.instance.history
    val items by store.items.collectAsState()
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val orig = items.sumOf { it.origSize }
            val now = items.sumOf { it.newSize }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Kul bachat", style = MaterialTheme.typography.labelLarge)
                    Text("${Fmt.size(orig - now)}  (${Fmt.saved(orig, now)}%)",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${items.size} files • ${Fmt.size(orig)} → ${Fmt.size(now)}", style = MaterialTheme.typography.bodySmall)
                    FlowRowKinds(items)
                }
            }
        }
        if (items.isEmpty()) item {
            Text("Abhi koi history nahi. Pehli file compress karo! 🚀", Modifier.padding(24.dp))
        }
        items(items, key = { it.time.toString() + it.outUri }) { h ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(h.kind.emoji, fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(h.time)),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton({ shareUri(ctx, Uri.parse(h.outUri), h.mime) }) { Icon(Icons.Default.Share, "Share") }
                        IconButton({ store.remove(h) }) { Icon(Icons.Default.Delete, "History se hatao") }
                    }
                    Box(Modifier.clickable { openUri(ctx, Uri.parse(h.outUri), h.mime) }) { ResultRow(h.origSize, h.newSize) }
                }
            }
        }
        if (items.isNotEmpty()) item {
            TextButton({ if (confirmClear) { store.clear(); confirmClear = false } else confirmClear = true }) {
                Text(if (confirmClear) "Pakka? Dobara dabao" else "History saaf karo (files delete nahi hongi)")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowKinds(items: List<HistoryItem>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
        FileKind.entries.forEach { k ->
            val n = items.count { it.kind == k }
            if (n > 0) Text("${k.emoji} $n", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ───────────────────────────── helpers ─────────────────────────────

private fun openUri(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Is file ko kholne wali app nahi mili", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "File ab available nahi hai", Toast.LENGTH_SHORT).show()
    }
}

private fun shareUri(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Share karo"))
}
