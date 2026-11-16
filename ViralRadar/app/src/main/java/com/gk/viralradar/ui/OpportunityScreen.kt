@file:OptIn(ExperimentalLayoutApi::class)

package com.gk.viralradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gk.viralradar.data.Fmt
import com.gk.viralradar.data.KeywordReport
import com.gk.viralradar.data.Opportunity

private enum class OppSort(val label: String) { SCORE("Best opportunity"), DEMAND("Sabse zyada demand"), LOW_COMP("Sabse kam competition"), LOW_SUPPLY("Sabse kam videos") }

@Composable
fun OpportunityScreen(vm: AppViewModel) {
    val suggestions by vm.oppSuggestions.collectAsStateWithLifecycle()
    val reports by vm.oppReports.collectAsStateWithLifecycle()
    val progress by vm.oppProgress.collectAsStateWithLifecycle()
    val err by vm.oppError.collectAsStateWithLifecycle()
    val suggestBusy by vm.oppSuggestBusy.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val watch by vm.watchlist.collectAsStateWithLifecycle()

    var seed by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableIntStateOf(720) }
    var picked by remember(suggestions) { mutableStateOf(suggestions.take(5).toSet()) }
    var sort by remember { mutableStateOf(OppSort.SCORE) }
    var expanded by remember { mutableStateOf<String?>(null) }

    fun runSeed() {
        val manual = seed.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (manual.size > 1) vm.analyzeKeywords(manual, hours) else vm.loadSuggestions(seed)
    }

    val sorted = remember(reports, sort) {
        when (sort) {
            OppSort.SCORE -> reports.sortedByDescending { it.score }
            OppSort.DEMAND -> reports.sortedByDescending { it.demand }
            OppSort.LOW_COMP -> reports.sortedBy { it.competition }
            OppSort.LOW_SUPPLY -> reports.sortedBy { it.supply }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(14.dp)) {
                Text("💎 Low Competition Finder", fontWeight = FontWeight.Black, fontSize = 20.sp)
                Text("Live data se dhoondo kaunse topics ki demand zyada hai par videos kam bane hain ya bade channels nahi hain.",
                    fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    seed, { seed = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Topic likho (e.g. prank, cricket, recipe)") },
                    supportingText = { Text("Ya kai keywords comma se: \"desi prank, family comedy, papa beta\"") },
                    trailingIcon = { IconButton(::runSeed) { Icon(Icons.Default.Lightbulb, null) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSeed() }),
                )
                insights?.topKeywords?.take(10)?.takeIf { it.isNotEmpty() }?.let { kws ->
                    Text("Trending se ideas:", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        kws.forEach { (k, _) -> SuggestionChip({ seed = k; vm.loadSuggestions(k) }, label = { Text(k) }) }
                    }
                }
                Text("Time window:", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(168, 720, 2160).forEach { h -> FilterChip(hours == h, { hours = h }, label = { Text("Pichhle " + Opportunity.windowLabel(h)) }) }
                }
                Button(::runSeed, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !suggestBusy && seed.isNotBlank()) {
                    Text(if (suggestBusy) "Searches dhoond rahe hain…" else "🔎 Related searches dhoondo (free)")
                }
            }
        }

        if (suggestions.isNotEmpty()) item {
            Column(Modifier.padding(horizontal = 14.dp)) {
                SectionTitle("Log ye search kar rahe hain (YouTube autocomplete)")
                Text("Jo keywords check karne hain unhe chuno:", fontSize = 12.sp, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    suggestions.forEach { k ->
                        FilterChip(k in picked, { picked = if (k in picked) picked - k else picked + k }, label = { Text(k) })
                    }
                }
                val cost = picked.size * 102
                Button(
                    { vm.analyzeKeywords(picked.toList(), hours) }, Modifier.fillMaxWidth().padding(top = 6.dp),
                    enabled = picked.isNotEmpty() && progress == null,
                ) { Text("📊 ${picked.size} keywords analyze karo (≈$cost quota units)") }
                Text("Free quota 10,000/day hai — ek baar me 5-8 keywords theek hain. Results 6 ghante cache rehte hain.",
                    fontSize = 11.sp, color = Muted)
            }
        }

        item {
            progress?.let { (d, t) ->
                Column(Modifier.padding(14.dp)) {
                    Text("Analyzing $d / $t …", fontSize = 13.sp)
                    LinearProgressIndicator({ if (t == 0) 0f else d.toFloat() / t }, Modifier.fillMaxWidth())
                }
            }
            ErrorBanner(err)
        }

        if (reports.isNotEmpty()) item {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Results (${reports.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                DropdownChip("↕ ${sort.label}", OppSort.entries.map { it.name to it.label }) { sort = OppSort.valueOf(it) }
            }
            Text("💎 GOLDEN = demand high + competition/videos low. Upar wale topics pe video banao.",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 14.dp))
        }

        items(sorted, key = { it.keyword }) { r ->
            KeywordCard(r, expanded == r.keyword, onToggle = { expanded = if (expanded == r.keyword) null else r.keyword }) {
                r.top.take(6).forEachIndexed { i, item ->
                    VideoCard(item, i + 1, watch.any { it.id == item.video.id },
                        onClick = { vm.selected.value = item },
                        onTrack = { if (vm.isTracked(item.video.id)) vm.untrack(item.video.id) else vm.track(item.video) })
                }
            }
        }

        if (reports.isEmpty() && suggestions.isEmpty()) item {
            Text(
                "Kaise kaam karta hai:\n\n" +
                    "1. Topic likho → app YouTube autocomplete se wo searches laati hai jo log abhi type kar rahe hain (free).\n" +
                    "2. Keywords chuno → har keyword ke liye chosen time window ke top 50 videos ka live data aata hai.\n" +
                    "3. Har keyword ko Opportunity Score milta hai:\n" +
                    "   • Demand (40%) – top videos kitne views la rahe hain\n" +
                    "   • Low competition (25%) – jeetne wale channels chhote hain ya bade\n" +
                    "   • Chhote channels ki jeet (20%) – <100K subs wale bhi top me hain?\n" +
                    "   • Kam videos (15%) – us window me kitne videos upload hue\n\n" +
                    "Video counts YouTube ka approximate estimate hain.",
                fontSize = 13.sp, color = Muted, modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun KeywordCard(r: KeywordReport, open: Boolean, onToggle: () -> Unit, videos: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(Modifier.clickable(onClick = onToggle).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.keyword, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(r.label, fontWeight = FontWeight.Black, fontSize = 13.sp, color = oppColor(r.score))
                }
                ScoreBadge(r.score, size = 50)
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Muted)
            }
            Spacer(Modifier.height(6.dp))
            Meter("Demand", r.demand, Mint, "median ${Fmt.count(r.medianViews)} views")
            Meter("Competition", r.competition, Hot, "median ${Fmt.count(r.medianSubs)} subs")
            Meter("Videos already", r.supply, Gold, "≈${Fmt.count(r.totalVideos)} in ${Opportunity.windowLabel(r.hoursBack)}")
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("⚡ ${Fmt.count(r.avgViewsPerHour.toLong())}/h avg")
                Chip("🐣 small wins ${(r.smallWin * 100).toInt()}%")
                if (r.inAutocomplete) Chip("🔎 searched", Mint.copy(alpha = .2f), Mint)
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                r.verdict.forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                Text("Is keyword ke top videos:", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (open) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .background(MaterialTheme.colorScheme.background)) { Column { videos() } }
        }
    }
}

private fun oppColor(s: Int) = when { s >= 70 -> Sky; s >= 55 -> Mint; s >= 40 -> Gold; else -> Hot }
