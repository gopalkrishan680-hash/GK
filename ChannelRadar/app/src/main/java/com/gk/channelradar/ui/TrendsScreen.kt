package com.gk.channelradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.channelradar.data.KeywordReport
import com.gk.channelradar.engine.Niches
import java.net.URLEncoder

private fun ytSearchUrl(q: String) = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")

@Composable
fun TrendsScreen(vm: AppViewModel) {
    var section by rememberSaveable { mutableStateOf(0) }
    val ctx = LocalContext.current
    val analyze: (String) -> Unit = { k -> vm.analyzeKeyword(k); section = 2 }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            PageHeader("Trends", "What people are searching for right now, and which topics have demand.", "🔥")
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("YouTube", "Google", "Demand").forEachIndexed { i, label ->
                    SegmentedButton(selected = section == i, onClick = { section = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
        }
        when (section) {
            0 -> youTubeSection(vm, analyze, open = { openUrl(ctx, ytSearchUrl(it)) })
            1 -> googleSection(vm, analyze, open = { openUrl(ctx, it) })
            else -> demandSection(vm, open = { openUrl(ctx, it) })
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ============================================================================ YouTube searches

private fun androidx.compose.foundation.lazy.LazyListScope.youTubeSection(
    vm: AppViewModel, analyze: (String) -> Unit, open: (String) -> Unit,
) {
    item {
        var seed by rememberSaveable { mutableStateOf("") }
        var deep by rememberSaveable { mutableStateOf(true) }
        Column {
            Text(
                "Live YouTube search suggestions. A phrase that pops up in many suggestion lists, near the top, is being searched a lot right now. Free, uses no quota.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = seed, onValueChange = { seed = it }, singleLine = true,
                placeholder = { Text("Enter a topic, e.g. ai, cricket, recipe") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (seed.isNotBlank()) vm.exploreIdeas(seed, deep) }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = deep, onCheckedChange = { deep = it })
                Spacer(Modifier.width(8.dp))
                Text("Deep explore (a–z variations)", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Button(onClick = { vm.exploreIdeas(seed, deep) }, enabled = seed.isNotBlank() && !vm.ideasLoading) { Text("Explore") }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { vm.exploreIdeas("", false) }, label = { Text("🔥 Hot across all niches") })
                Niches.all.forEach { n ->
                    AssistChip(onClick = { seed = n.keywords.first(); vm.exploreIdeas(n.keywords.first(), deep) }, label = { Text("${n.emoji} ${n.name}") })
                }
            }
            if (vm.ideasLoading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { vm.ideasProgress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)))
            }
        }
    }
    if (vm.ideas.isEmpty() && !vm.ideasLoading) item {
        EmptyState("💡", "Discover search demand", "Type a topic, or tap \"Hot across all niches\" to see what people are searching on YouTube right now.")
    } else if (vm.ideas.isNotEmpty()) {
        item { SectionTitle(vm.ideasTitle) }
        itemsIndexed(vm.ideas, key = { _, it -> "idea_" + it.phrase }) { i, idea ->
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(idea.phrase, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Meter(idea.demand, Modifier.width(90.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("${idea.demand.toInt()} · in ${idea.appearances} lists", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    KeywordActions(idea.phrase, vm, analyze) { open(idea.phrase) }
                }
            }
        }
    }
}

// ============================================================================ Google Trends

private fun androidx.compose.foundation.lazy.LazyListScope.googleSection(
    vm: AppViewModel, analyze: (String) -> Unit, open: (String) -> Unit,
) {
    item {
        LaunchedEffect(Unit) { if (vm.googleTrends.isEmpty()) vm.loadGoogleTrends() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Today's fastest-rising Google searches in ${vm.config.region}. Great for timely videos. Free, uses no quota.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
            )
            if (vm.trendsLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { vm.loadGoogleTrends() }) { Icon(Icons.Filled.Refresh, "Reload") }
        }
    }
    if (vm.googleTrends.isEmpty() && !vm.trendsLoading) item {
        EmptyState("📈", "No trends loaded", "Tap refresh to load today's trending searches.")
    }
    items(vm.googleTrends, key = { "gt_" + it.title }) { t ->
        SoftCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 8.dp, end = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (t.traffic.isNotBlank()) Tag("🔎 ${t.traffic} searches", color = GoodGreen, bg = GoodGreen.copy(alpha = 0.14f))
                            if (t.time > 0) Tag(ago(t.time))
                        }
                    }
                    KeywordActions(t.title, vm, analyze) { open(ytSearchUrl(t.title)) }
                }
                if (t.newsTitle.isNotBlank()) {
                    TextButton(onClick = { if (t.newsUrl.isNotBlank()) open(t.newsUrl) }, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            "📰 ${t.newsTitle}" + if (t.newsSource.isNotBlank()) " · ${t.newsSource}" else "",
                            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================ Demand check

private fun androidx.compose.foundation.lazy.LazyListScope.demandSection(vm: AppViewModel, open: (String) -> Unit) {
    item {
        var kw by rememberSaveable { mutableStateOf("") }
        Column {
            Text(
                "Check any keyword: how many views its recent videos get (demand), and whether SMALL channels are winning on it (opportunity). Costs ~102 units.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = kw, onValueChange = { kw = it }, singleLine = true,
                    placeholder = { Text("Keyword to check") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.analyzeKeyword(kw) }),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.analyzeKeyword(kw) }, enabled = kw.isNotBlank() && vm.settings.apiKeys.isNotEmpty()) { Text("Check") }
            }
        }
    }
    vm.analyzing.forEach { k ->
        item(key = "an_$k") {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking \"$k\"…", fontSize = 13.sp)
                }
            }
        }
    }
    if (vm.reports.isEmpty() && vm.analyzing.isEmpty()) item {
        EmptyState("🎯", "No keywords checked yet", "Type a keyword above, or tap 🔍 next to any idea in the YouTube or Google tabs.")
    }
    items(vm.reports, key = { "rep_" + it.keyword }) { r -> ReportCard(r, vm, open) }
}

@Composable
private fun ReportCard(r: KeywordReport, vm: AppViewModel, open: (String) -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.keyword, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${r.region} · checked ${ago(r.time)} · ${r.videosChecked} videos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ScoreBadge(r.opportunity)
            }
            Spacer(Modifier.height(8.dp))
            Meter(r.opportunity, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(r.verdict, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Row {
                Stat("Typical views", fmtCount(r.medianViews), Modifier.weight(1f))
                Stat("Top video", fmtCount(r.topViews), Modifier.weight(1f))
                Stat("Fresh (7d)", "${(r.freshShare * 100).toInt()}%", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Stat("From small ch.", "${(r.smallChannelShare * 100).toInt()}%", Modifier.weight(1f))
                Stat("Small ch. win rate", "${(r.smallWinRate * 100).toInt()}%", Modifier.weight(1f), if (r.smallWinRate >= 0.5) GoodGreen else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
            }
            if (r.examples.isNotEmpty()) {
                SectionTitle("Small channels winning on it")
                r.examples.forEach { sv ->
                    VideoRow(sv) { open(sv.video.url) }
                    Text(
                        "${sv.video.channelTitle} · ${fmtCount(r.exampleChannels[sv.video.channelId] ?: 0)} subs",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
            Row {
                FilledTonalButton(onClick = { vm.useInScan(r.keyword) }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Use in scan")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.analyzeKeyword(r.keyword) }) { Icon(Icons.Filled.Refresh, "Re-check") }
                IconButton(onClick = { vm.deleteReport(r.keyword) }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun KeywordActions(k: String, vm: AppViewModel, analyze: (String) -> Unit, onOpen: () -> Unit) {
    Row {
        IconButton(onClick = { analyze(k) }, modifier = Modifier.size(40.dp)) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Search, "Check demand", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        IconButton(onClick = { vm.useInScan(k) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.Add, "Add to scan", modifier = Modifier.size(20.dp)) }
        IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.PlayArrow, "Open on YouTube", modifier = Modifier.size(20.dp)) }
    }
}
