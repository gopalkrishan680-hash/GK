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
    val analyzeTop: (List<String>) -> Unit = { ks -> vm.analyzeMany(ks); section = 2 }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            PageHeader("Trends", "What people are searching for right now, and which topics have demand.", "🔥")
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("YouTube", "Google", "Keywords").forEachIndexed { i, label ->
                    SegmentedButton(selected = section == i, onClick = { section = i }, shape = SegmentedButtonDefaults.itemShape(i, 3)) {
                        Text(label, fontSize = 13.sp)
                    }
                }
            }
        }
        when (section) {
            0 -> youTubeSection(vm, analyze, open = { openUrl(ctx, ytSearchUrl(it)) }, analyzeTop = analyzeTop)
            1 -> googleSection(vm, analyze, open = { openUrl(ctx, it) })
            else -> demandSection(vm, open = { openUrl(ctx, it) })
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

// ============================================================================ YouTube searches

private fun androidx.compose.foundation.lazy.LazyListScope.youTubeSection(
    vm: AppViewModel, analyze: (String) -> Unit, open: (String) -> Unit, analyzeTop: (List<String>) -> Unit,
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
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(vm.ideasTitle, Modifier.weight(1f))
                TextButton(
                    onClick = { analyzeTop(vm.ideas.take(5).map { it.phrase }) },
                    enabled = vm.settings.apiKeys.isNotEmpty()
                ) { Text("Check top 5 (~510 units)", fontSize = 12.sp) }
            }
        }
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
                        vm.reports.firstOrNull { it.keyword.equals(idea.phrase, true) }?.let { r -> MiniMetrics(r) }
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
                "Keyword research: estimated monthly searches, demand, competition and opportunity for any keyword. Costs ~102 units per keyword.",
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
    if (vm.reports.size >= 2) item { CompareTable(vm) }
    items(vm.reports, key = { "rep_" + it.keyword }) { r -> ReportCard(r, vm, open) }
}

fun competitionLabel(c: Double) = when {
    c < 35 -> "Low"; c < 60 -> "Medium"; c < 80 -> "High"; else -> "Very high"
}

fun demandLabel(d: Double) = when {
    d < 25 -> "Low"; d < 50 -> "Medium"; d < 75 -> "High"; else -> "Very high"
}

/** colour where LOW is good (competition) */
fun lowGoodColor(c: Double) = when {
    c < 35 -> GoodGreen; c < 60 -> WarnAmber; c < 80 -> androidx.compose.ui.graphics.Color(0xFFFF7A45); else -> androidx.compose.ui.graphics.Color(0xFFFF4D4F)
}

/** colour where HIGH is good (demand) */
fun highGoodColor(d: Double) = lowGoodColor(100 - d)

fun searchRange(n: Long): String = if (n <= 0) "–" else "${fmtCount(n / 2)}–${fmtCount(n * 2)}"

@Composable
private fun MiniMetrics(r: KeywordReport) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 5.dp)) {
        Tag("🔎 ~${fmtCount(r.estSearches)}/mo")
        Tag("Comp ${competitionLabel(r.competition)}", color = lowGoodColor(r.competition), bg = lowGoodColor(r.competition).copy(alpha = 0.14f))
        Tag("Opp ${r.opportunity.toInt()}", color = scoreColor(r.opportunity), bg = scoreColor(r.opportunity).copy(alpha = 0.14f))
    }
}

@Composable
private fun BigMetric(label: String, value: String, sub: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1)
        Text(sub, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private enum class CmpSort(val label: String) { OPP("Opportunity"), SEARCH("Searches"), COMP("Low competition") }

@Composable
private fun CompareTable(vm: AppViewModel) {
    var sort by rememberSaveable { mutableStateOf(CmpSort.OPP) }
    val rows = when (sort) {
        CmpSort.OPP -> vm.reports.sortedByDescending { it.opportunity }
        CmpSort.SEARCH -> vm.reports.sortedByDescending { it.estSearches }
        CmpSort.COMP -> vm.reports.sortedBy { it.competition }
    }
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("📋 Compare keywords", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CmpSort.entries.forEach { c ->
                    androidx.compose.material3.FilterChip(selected = sort == c, onClick = { sort = c }, label = { Text(c.label, fontSize = 12.sp) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row {
                Text("Keyword", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Searches", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(62.dp))
                Text("Comp.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(62.dp))
                Text("Opp.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
            }
            rows.take(25).forEach { r ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.keyword, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("~" + fmtCount(r.estSearches), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(62.dp))
                    Text(competitionLabel(r.competition), fontSize = 12.sp, color = lowGoodColor(r.competition), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(62.dp))
                    Text("${r.opportunity.toInt()}", fontSize = 13.sp, color = scoreColor(r.opportunity), fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                }
            }
            Text("Searches are estimates (see the ⓘ note on each card).", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReportCard(r: KeywordReport, vm: AppViewModel, open: (String) -> Unit) {
    var expanded by rememberSaveable(r.keyword) { mutableStateOf(false) }
    SoftCard(modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.keyword, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${r.region} · checked ${ago(r.time)} · ${r.videosChecked} top videos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ScoreBadge(r.opportunity)
                    Text("opportunity", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BigMetric("Est. searches / month", "~" + fmtCount(r.estSearches), searchRange(r.estSearches), MaterialTheme.colorScheme.primary, Modifier.weight(1.2f))
                BigMetric("Demand", demandLabel(r.searchIndex), "${r.searchIndex.toInt()}/100", highGoodColor(r.searchIndex), Modifier.weight(1f))
                BigMetric("Competition", competitionLabel(r.competition), "${r.competition.toInt()}/100", lowGoodColor(r.competition), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(r.verdict, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row {
                Stat("Views (30d)", fmtCount(r.monthlyViews), Modifier.weight(1f))
                Stat("New videos (30d)", if (r.recentUploads > 0) "~" + fmtCount(r.recentUploads) else "–", Modifier.weight(1f))
                Stat("Typical views", fmtCount(r.medianViews), Modifier.weight(1f))
            }
            Text(
                if (expanded) "▲ Less" else "▼ Details, best time to post, related keywords",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp)
            )
            if (expanded) {
                SectionTitle("Competition breakdown")
                Row {
                    Stat("Big channels in top", "${(r.bigChannelShare * 100).toInt()}%", Modifier.weight(1f), lowGoodColor(r.bigChannelShare * 100))
                    Stat("Typical channel size", fmtCount(r.medianTopSubs) + " subs", Modifier.weight(1f))
                    Stat("Small ch. win rate", "${(r.smallWinRate * 100).toInt()}%", Modifier.weight(1f), if (r.smallWinRate >= 0.5) GoodGreen else MaterialTheme.colorScheme.onSurface)
                }
                SectionTitle("Search popularity")
                Text(
                    when {
                        r.prefixLength in 1..3 -> "🔥 YouTube suggests it after typing just ${r.prefixLength} letter${if (r.prefixLength > 1) "s" else ""} – very heavily searched."
                        r.prefixLength > 3 -> "YouTube suggests it after typing ${r.prefixLength} letters."
                        else -> "YouTube doesn't autocomplete this exact phrase – searches are low or it's very new."
                    } + " Top results uploaded in the last 7 days: ${(r.freshShare * 100).toInt()}%.",
                    fontSize = 12.sp
                )
                SectionTitle("What works")
                Row {
                    Stat("Shorts in top", "${(r.shortsShare * 100).toInt()}%", Modifier.weight(1f))
                    Stat("Typical length", if (r.typicalLengthSec > 0) fmtDuration(r.typicalLengthSec) else "–", Modifier.weight(1f))
                    Stat(
                        "Best time to post",
                        if (r.bestHour >= 0) "${r.bestDay.take(3)} ${"%d %s".format(if (r.bestHour % 12 == 0) 12 else r.bestHour % 12, if (r.bestHour < 12) "AM" else "PM")}" else "–",
                        Modifier.weight(1f)
                    )
                }
                if (r.titleWords.isNotEmpty()) {
                    SectionTitle("Words used in top titles")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        r.titleWords.forEach { Tag(it) }
                    }
                }
                if (r.related.isNotEmpty()) {
                    SectionTitle("Related searches (tap to check)")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.related.forEach { k ->
                            AssistChip(onClick = { vm.analyzeKeyword(k) }, label = { Text(k, fontSize = 12.sp) })
                        }
                    }
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
                Text(
                    "ⓘ YouTube doesn't publish exact search numbers. \"Est. searches\" is Channel Radar's own estimate from YouTube autocomplete and the views recent videos get, so treat it as a range. Demand, competition and opportunity are best used to compare keywords.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.useInScan(r.keyword) }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Find channels")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { open(ytSearchUrl(r.keyword)) }) { Icon(Icons.Filled.PlayArrow, "Open on YouTube") }
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
