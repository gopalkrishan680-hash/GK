package com.gk.channelradar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.channelradar.data.ChannelResult
import com.gk.channelradar.data.ScoredVideo

private enum class Sort(val label: String) {
    SCORE("Breakout score"), RATIO("Views ÷ subs"), OUTLIER("Outlier ×"), SPEED("Views / hour"),
    VIEWS("Total views"), SMALLEST("Smallest channel"), NEWEST("Newest video")
}

private enum class Fmt { ALL, SHORTS, LONG }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultsScreen(vm: AppViewModel) {
    val res = vm.result
    val ctx = LocalContext.current
    if (res == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Abhi koi result nahi hai.\nDiscover tab se scan chalao.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var videoMode by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(Sort.SCORE) }
    var fmt by rememberSaveable { mutableStateOf(Fmt.ALL) }
    var niche by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyNew by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortMenu by remember { mutableStateOf(false) }
    var nicheMenu by remember { mutableStateOf(false) }
    var showWords by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }

    fun fmtOk(sv: ScoredVideo) = when (fmt) {
        Fmt.ALL -> true; Fmt.SHORTS -> sv.video.isShort; Fmt.LONG -> !sv.video.isShort
    }

    val channels = remember(res, sort, fmt, niche, onlyNew, query, vm.hidden) {
        res.channels.asSequence()
            .filter { it.channel.id !in vm.hidden }
            .map { r -> r.copy(hits = r.hits.filter { fmtOk(it) }) }
            .filter { it.hits.isNotEmpty() }
            .filter { niche == null || niche in it.niches }
            .filter { !onlyNew || it.isNew }
            .filter {
                query.isBlank() || it.channel.title.contains(query, true) ||
                    it.hits.any { h -> h.video.title.contains(query, true) }
            }
            .sortedWith(
                when (sort) {
                    Sort.SCORE -> compareByDescending { it.score }
                    Sort.RATIO -> compareByDescending { it.hits.maxOf { h -> h.ratio } }
                    Sort.OUTLIER -> compareByDescending { it.hits.maxOf { h -> h.outlier ?: 0.0 } }
                    Sort.SPEED -> compareByDescending { it.hits.maxOf { h -> h.viewsPerHour } }
                    Sort.VIEWS -> compareByDescending { it.hits.sumOf { h -> h.video.views } }
                    Sort.SMALLEST -> compareBy { it.channel.subs }
                    Sort.NEWEST -> compareByDescending { it.hits.maxOf { h -> h.video.publishedAt } }
                }
            ).toList()
    }
    val videos = remember(channels, sort) {
        channels.flatMap { r -> r.hits.map { r to it } }.sortedWith(
            when (sort) {
                Sort.RATIO -> compareByDescending { it.second.ratio }
                Sort.OUTLIER -> compareByDescending { it.second.outlier ?: 0.0 }
                Sort.SPEED -> compareByDescending { it.second.viewsPerHour }
                Sort.VIEWS -> compareByDescending { it.second.video.views }
                Sort.SMALLEST -> compareBy { it.first.channel.subs }
                Sort.NEWEST -> compareByDescending { it.second.video.publishedAt }
                Sort.SCORE -> compareByDescending { it.second.score }
            }
        )
    }
    val allNiches = remember(res) { res.channels.flatMap { it.niches }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri, channels)
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${channels.size} channels · ${res.videosScanned} videos scanned · ${res.quotaUsed} units · ${ago(res.time)}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { exporter.launch("small_channels_${System.currentTimeMillis() / 1000}.csv") }) {
                    Icon(Icons.Filled.Share, "Export CSV")
                }
            }
        }

        if (res.warnings.isNotEmpty() || res.keywordsUsed.isNotEmpty()) item {
            TextButton(onClick = { showInfo = !showInfo }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    (if (showInfo) "▲ " else "▼ ") + "Scan details" + if (res.warnings.isNotEmpty()) " (${res.warnings.size} warnings)" else "",
                    fontSize = 12.sp
                )
            }
            if (showInfo) Column {
                Text("Keywords: " + res.keywordsUsed.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                res.warnings.forEach { Text("⚠ $it", fontSize = 12.sp, color = WarnAmber) }
            }
        }

        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !videoMode, onClick = { videoMode = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Channels") }
                SegmentedButton(selected = videoMode, onClick = { videoMode = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Videos (${videos.size})") }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    FilterChip(selected = true, onClick = { sortMenu = true }, label = { Text("Sort: ${sort.label}") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortMenu = false }) }
                    }
                }
                Box {
                    FilterChip(selected = niche != null, onClick = { nicheMenu = true }, label = { Text(niche ?: "All niches") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) })
                    DropdownMenu(expanded = nicheMenu, onDismissRequest = { nicheMenu = false }) {
                        DropdownMenuItem(text = { Text("All niches") }, onClick = { niche = null; nicheMenu = false })
                        allNiches.forEach { (n, cnt) ->
                            DropdownMenuItem(text = { Text("$n ($cnt)") }, onClick = { niche = n; nicheMenu = false })
                        }
                    }
                }
                FilterChip(selected = fmt == Fmt.SHORTS, onClick = { fmt = if (fmt == Fmt.SHORTS) Fmt.ALL else Fmt.SHORTS }, label = { Text("📱 Shorts") })
                FilterChip(selected = fmt == Fmt.LONG, onClick = { fmt = if (fmt == Fmt.LONG) Fmt.ALL else Fmt.LONG }, label = { Text("🎬 Long") })
                FilterChip(selected = onlyNew, onClick = { onlyNew = !onlyNew }, label = { Text("🆕 Only new") })
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Channel ya title me search…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, null) } },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (res.trendingWords.isNotEmpty()) item {
            Card(
                onClick = { showWords = !showWords },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(Modifier.padding(12.dp).fillMaxWidth()) {
                    Text("🔥 Breakout titles me common words " + if (showWords) "▲" else "▼", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (showWords) {
                        Text("Ye words kai chote channels ke viral titles me baar-baar aa rahe hain. Tap karke filter karo.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            res.trendingWords.forEach { w ->
                                FilterChip(selected = query == w.word, onClick = { query = if (query == w.word) "" else w.word }, label = { Text("${w.word} · ${w.count}") })
                            }
                        }
                    }
                }
            }
        }

        if (!videoMode) {
            if (channels.isEmpty()) item {
                Text(
                    "Is filter me koi channel nahi. Filters badlo, ya Discover me 'Max subs' badhao / 'Min views' kam karo.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)
                )
            }
            items(channels, key = { it.channel.id }) { r ->
                ChannelCard(
                    r = r, watched = vm.isWatched(r.channel.id),
                    onClick = { vm.openDetail(r) }, onWatch = { vm.toggleWatch(r.channel) }
                )
            }
        } else {
            items(videos, key = { it.second.video.id }) { (r, sv) ->
                Card(
                    onClick = { openUrl(ctx, sv.video.url) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        VideoRow(sv)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${r.channel.title} · ${fmtCount(r.channel.subs)} subs",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1
                            )
                            TextButton(onClick = { vm.openDetail(r) }) { Text("Channel →", fontSize = 12.sp) }
                            Spacer(Modifier.width(4.dp))
                            Tag("%.0f".format(sv.score), color = scoreColor(sv.score))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
