@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.gk.viralradar.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gk.viralradar.data.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Trending

@Composable
fun TrendingScreen(vm: AppViewModel) {
    val st by vm.trending.collectAsStateWithLifecycle()
    val region by vm.region.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val cats by vm.categories.collectAsStateWithLifecycle()
    val fmt by vm.format.collectAsStateWithLifecycle()
    val sort by vm.sortBy.collectAsStateWithLifecycle()
    val watch by vm.watchlist.collectAsStateWithLifecycle()
    var tier by rememberSaveable { mutableStateOf<Tier?>(null) }

    // Live auto-refresh: re-fetch every 10 minutes while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            val s = vm.trending.value
            val every = if (s.expensive) 60 * 60_000 else 10 * 60_000
            if (s.error == null && System.currentTimeMillis() - s.updatedAt > every) vm.refreshTrending()
            delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DropdownChip("🌍 ${REGIONS.firstOrNull { it.first == region }?.second ?: region}", REGIONS.map { it.first to it.second }) { vm.setRegion(it) }
            DropdownChip("📂 ${cats.firstOrNull { it.first == cat }?.second ?: "All categories"}", listOf("" to "All categories") + cats) {
                vm.setCategory(it.ifBlank { null })
            }
            DropdownChip("🎬 ${fmt.label}", FormatFilter.entries.map { it.name to it.label }) { vm.format.value = FormatFilter.valueOf(it) }
            DropdownChip("↕ ${sort.label}", SortBy.entries.map { it.name to it.label }) { vm.sortBy.value = SortBy.valueOf(it) }
        }
        if (st.updatedAt > 0) {
            Text(
                "Live YouTube trending • updated ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(st.updatedAt))} • neeche kheencho refresh ke liye",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        ErrorBanner(st.error)
        st.note?.let {
            Text("ℹ️ $it", fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp))
        }
        PullToRefreshBox(isRefreshing = st.loading, onRefresh = { vm.refreshTrending() }, modifier = Modifier.fillMaxSize()) {
            val base = remember(st.items, fmt, sort) { vm.sorted(st.items, fmt, sort) }
            val list = remember(base, tier) { base.filter { tier?.matches(it) ?: true } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (base.isNotEmpty()) item { TopPicksHeader(base, tier) { tier = if (tier == it) null else it } }
                if (base.isNotEmpty() && list.isEmpty()) item { EmptyTier(tier) { tier = null } }
                itemsIndexed(list, key = { _, it -> it.video.id }) { i, item ->
                    VideoCard(item, i + 1, watch.any { it.id == item.video.id },
                        onClick = { vm.selected.value = item },
                        onTrack = { if (vm.isTracked(item.video.id)) vm.untrack(item.video.id) else vm.track(item.video) })
                }
            }
        }
    }
}

enum class Tier(val title: String, val hint: String) {
    VIRAL("🔥 Viral", "Score 75+"),
    RISING("🚀 Rising", "Score 58-74"),
    BREAKOUT("💥 Breakout 5x+", "Subs se 5x+ views");

    fun matches(s: Scored) = when (this) {
        VIRAL -> s.score.score >= 75
        RISING -> s.score.score in 58..74
        BREAKOUT -> (s.score.outlier ?: 0.0) >= 5
    }

    val color get() = when (this) { VIRAL -> Hot; RISING -> Gold; BREAKOUT -> Sky }
}

@Composable
private fun TopPicksHeader(list: List<Scored>, selected: Tier?, onPick: (Tier) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tier.entries.forEach { t ->
                val on = selected == t
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .background(if (on) t.color.copy(alpha = .22f) else MaterialTheme.colorScheme.surfaceVariant)
                        .border(2.dp, if (on) t.color else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { onPick(t) }.padding(10.dp),
                ) {
                    Text(t.title, fontSize = 11.sp, color = if (on) t.color else Muted, maxLines = 1)
                    Text("${list.count(t::matches)}", fontSize = 20.sp, fontWeight = FontWeight.Black, color = t.color)
                    Text(if (on) "✕ hatao" else "Tap karo ›", fontSize = 10.sp, color = Muted)
                }
            }
        }
        selected?.let { t ->
            Text("${t.title} videos (${t.hint}) • ${list.count(t::matches)} mile", fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = t.color, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun EmptyTier(t: Tier?, onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Abhi is list me koi ${t?.title ?: ""} video nahi hai.", color = Muted)
        TextButton(onClear) { Text("Saare videos dikhao") }
    }
}

@Composable
fun DropdownChip(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(label, maxLines = 1) })
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { (k, v) -> DropdownMenuItem(text = { Text(v) }, onClick = { open = false; onPick(k) }) }
        }
    }
}

@Composable
fun ErrorBanner(err: String?) {
    if (err == null) return
    Text(
        "⚠️ $err", color = Color.White, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFB91C1C)).padding(10.dp),
    )
}

// ---------------------------------------------------------------- Discover

@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val st by vm.discover.collectAsStateWithLifecycle()
    val sort by vm.sortBy.collectAsStateWithLifecycle()
    val watch by vm.watchlist.collectAsStateWithLifecycle()
    var q by rememberSaveable { mutableStateOf("") }
    var hours by rememberSaveable { mutableIntStateOf(48) }
    var shortsOnly by rememberSaveable { mutableStateOf(false) }
    var tier by rememberSaveable { mutableStateOf<Tier?>(null) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp),
            label = { Text("Niche keyword ya YouTube link") },
            placeholder = { Text("e.g. funny prank, cricket, ai tools…") },
            trailingIcon = { IconButton({ vm.discover(q, hours, shortsOnly) }) { Icon(Icons.Default.Search, null) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.discover(q, hours, shortsOnly) }),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(6 to "6 ghante", 24 to "24 ghante", 48 to "2 din", 168 to "7 din", 720 to "30 din").forEach { (h, l) ->
                FilterChip(hours == h, { hours = h }, label = { Text(l) })
            }
            FilterChip(shortsOnly, { shortsOnly = !shortsOnly }, label = { Text("Shorts only") })
            DropdownChip("↕ ${sort.label}", SortBy.entries.map { it.name to it.label }) { vm.sortBy.value = SortBy.valueOf(it) }
        }
        Text(
            "Ye tool tumhare niche me abhi upload hue videos me se wo dhundta hai jo channel size ke hisaab se bahut tez chal rahe hain — yahi agle viral videos hote hain. (1 search ≈ 102 quota units)",
            fontSize = 11.sp, color = Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        ErrorBanner(st.error)
        if (st.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        val base = remember(st.items, sort) { vm.sorted(st.items, FormatFilter.ALL, sort) }
        val list = remember(base, tier) { base.filter { tier?.matches(it) ?: true } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (base.isNotEmpty()) item { TopPicksHeader(base, tier) { tier = if (tier == it) null else it } }
            if (base.isNotEmpty() && list.isEmpty()) item { EmptyTier(tier) { tier = null } }
            itemsIndexed(list, key = { _, it -> it.video.id }) { i, item ->
                VideoCard(item, i + 1, watch.any { it.id == item.video.id },
                    onClick = { vm.selected.value = item },
                    onTrack = { if (vm.isTracked(item.video.id)) vm.untrack(item.video.id) else vm.track(item.video) })
            }
        }
    }
}

// ---------------------------------------------------------------- Video detail

@Composable
fun VideoDetailScreen(vm: AppViewModel, item: Scored) {
    BackHandler { vm.selected.value = null }
    val ctx = LocalContext.current
    val watch by vm.watchlist.collectAsStateWithLifecycle()
    val tracked = watch.any { it.id == item.video.id }
    val v = item.video
    val s = item.score
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box {
            AsyncImage(v.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16 / 9f))
            IconButton({ vm.selected.value = null }, Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000))) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
        }
        Column(Modifier.padding(14.dp)) {
            Text(v.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("${v.channelTitle} • ${v.subs?.let { Fmt.count(it) + " subscribers" } ?: "subscribers hidden"}", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(s.score, size = 72)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.label, fontWeight = FontWeight.Black, fontSize = 22.sp, color = scoreColor(s.score))
                    Text("Viral Score / 100", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("Views", Fmt.count(v.views), Modifier.weight(1f))
                StatBox("Likes", Fmt.count(v.likes), Modifier.weight(1f))
                StatBox("Comments", Fmt.count(v.comments), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("Views/hour", Fmt.count(s.viewsPerHour.toLong()), Modifier.weight(1f), Gold)
                StatBox("Age", Fmt.age(s.ageHours), Modifier.weight(1f))
                StatBox(if (v.liveNow) "Live dekh rahe" else "Length",
                    when { v.liveNow -> v.concurrentViewers?.let { Fmt.count(it) } ?: "LIVE"; v.isShort -> "Short"; else -> Fmt.duration(v.durationSec) },
                    Modifier.weight(1f), if (v.liveNow) Hot else MaterialTheme.colorScheme.onSurface)
            }

            SectionTitle("📊 Score breakdown")
            Meter("Velocity (40%)", s.velocityPart, Hot, "${Fmt.count(s.viewsPerHour.toLong())}/hr")
            Meter("Outlier vs channel (25%)", s.outlierPart, Sky, s.outlier?.let { "%.2fx subs".format(it) } ?: "n/a")
            Meter("Engagement (20%)", s.engagementPart, Mint, "%.2f%%".format(s.engagement * 100))
            Meter("Freshness (15%)", s.freshnessPart, Gold, Fmt.age(s.ageHours))

            SectionTitle("🔮 Prediction")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("Next 24h tak", "~" + Fmt.count(s.predicted24h), Modifier.weight(1f), Mint)
                StatBox("7 din tak", "~" + Fmt.count(s.predicted7d), Modifier.weight(1f), Mint)
            }
            Text("Estimate current speed + engagement se. Track karoge to real hourly data se aur accurate prediction milega.",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))

            SectionTitle("🧠 Kyun?")
            s.reasons.forEach { Text("• $it", fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) }

            if (v.tags.isNotEmpty()) {
                SectionTitle("🏷 Tags (copy karne layak)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    v.tags.take(30).forEach { Chip(it) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.url))) }, Modifier.weight(1f)) {
                    Icon(Icons.Default.PlayArrow, null); Text(" YouTube")
                }
                OutlinedButton({ if (tracked) vm.untrack(v.id) else vm.track(v) }, Modifier.weight(1f)) {
                    Icon(if (tracked) Icons.Default.Check else Icons.Default.Add, null); Text(if (tracked) " Tracking" else " Track")
                }
                IconButton({
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "${v.title}\nViral Score: ${s.score} ${s.label}\n${v.url}"), "Share"))
                }) { Icon(Icons.Default.Share, null) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- Tracker

@Composable
fun TrackerScreen(vm: AppViewModel) {
    val list by vm.watchlist.collectAsStateWithLifecycle()
    val busy by vm.watchBusy.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text("YouTube link paste karo (apna ya kisi ka)") })
            IconButton({ vm.trackByUrl(url); url = "" }) { Icon(Icons.Default.AddCircle, null, tint = Hot) }
        }
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} videos • background me har ${vm.prefs.intervalMin} min update", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
            TextButton({ vm.refreshWatchlist() }, enabled = !busy) { Icon(Icons.Default.Refresh, null); Text(" Abhi update") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (list.isEmpty()) {
            Text(
                "Koi video track nahi ho raha.\n\nKisi bhi video pe 🔖 dabao ya link paste karo. App har interval pe uske views save karegi aur batayegi ki video ki speed badh rahi hai (viral hone wala hai) ya gir rahi hai. Speed tez badhne pe notification bhi aayega.",
                color = Muted, modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            itemsIndexed(list.reversed(), key = { _, it -> it.id }) { _, t ->
                val st = remember(t) { Tracker.stats(t) }
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable { vm.selectedTrack.value = t.id }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(t.thumbnail, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(96.dp).height(56.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("👁 ${Fmt.count(st.currentViews)} • ${st.recentVph?.let { Fmt.count(it.toLong()) + "/h" } ?: "-"}", fontSize = 12.sp, color = Muted)
                            Text(st.status, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = when { (st.acceleration ?: 0.0) > .25 -> Mint; (st.acceleration ?: 0.0) < -.25 -> Hot; else -> Muted })
                        }
                        st.score?.let { ScoreBadge(it.score, size = 40) }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackDetailScreen(vm: AppViewModel, id: String) {
    BackHandler { vm.selectedTrack.value = null }
    val list by vm.watchlist.collectAsStateWithLifecycle()
    val t = list.firstOrNull { it.id == id } ?: run { vm.selectedTrack.value = null; return }
    val st = remember(t) { Tracker.stats(t) }
    val ctx = LocalContext.current
    val velocity = remember(t) {
        t.snapshots.zipWithNext().mapNotNull { (a, b) ->
            val h = (b.t - a.t) / 3_600_000.0
            if (h > 0.01) b.t to ((b.views - a.views) / h).coerceAtLeast(0.0) else null
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.selectedTrack.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
            Text(t.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("Views", Fmt.count(st.currentViews), Modifier.weight(1f))
            StatBox("Abhi speed", st.recentVph?.let { Fmt.count(it.toLong()) + "/h" } ?: "-", Modifier.weight(1f), Gold)
            StatBox("Change", st.acceleration?.let { "%+d%%".format((it * 100).toInt()) } ?: "-", Modifier.weight(1f),
                if ((st.acceleration ?: 0.0) >= 0) Mint else Hot)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("Status", st.status, Modifier.weight(1.4f))
            StatBox("24h prediction", st.predicted24h?.let { "~" + Fmt.count(it) } ?: "-", Modifier.weight(1f), Mint)
        }
        SectionTitle("📈 Total views")
        LineChart(t.snapshots.map { it.t to it.views.toDouble() }, Hot, Modifier.fillMaxWidth().height(160.dp))
        SectionTitle("⚡ Speed (views/hour)")
        LineChart(velocity, Gold, Modifier.fillMaxWidth().height(140.dp))
        Text("${t.snapshots.size} snapshots • tracking since ${SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(t.addedAt))}",
            fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        st.score?.let { s ->
            SectionTitle("🧠 Analysis (score ${s.score} ${s.label})")
            s.reasons.forEach { Text("• $it", fontSize = 14.sp) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/${t.id}"))) }, Modifier.weight(1f)) { Text("YouTube pe kholo") }
            OutlinedButton({ vm.untrack(t.id); vm.selectedTrack.value = null }, Modifier.weight(1f)) { Text("Tracking band karo") }
        }
    }
}

// ---------------------------------------------------------------- Insights

@Composable
fun InsightsScreen(vm: AppViewModel) {
    val r by vm.insights.collectAsStateWithLifecycle()
    val region by vm.region.collectAsStateWithLifecycle()
    var title by rememberSaveable { mutableStateOf("") }
    val check = remember(title, r) { Insights.checkTitle(title, r) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Algorithm Insights", fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text("Abhi ke ${r?.sampleSize ?: 0} trending videos ($region) se nikala gaya pattern — Trending tab refresh hone pe update hota hai.",
            fontSize = 12.sp, color = Muted)

        SectionTitle("✍️ Title Checker")
        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Apne video ka title likho") })
        if (title.isNotBlank()) {
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreBadge(check.first, size = 52); Spacer(Modifier.width(10.dp))
                Text("Title score", fontWeight = FontWeight.Bold)
            }
            check.second.forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 1.dp)) }
        }

        val rep = r ?: run {
            Text("Data load ho raha hai… Pehle Settings me API key daalo aur Trending tab kholo.", color = Muted, modifier = Modifier.padding(top = 20.dp))
            return@Column
        }

        SectionTitle("⏰ Best upload time (tumhare time zone me)")
        Text("Top: " + rep.bestHours.joinToString { hourLabel(it) }, fontWeight = FontWeight.Bold, color = Gold)
        val maxH = rep.uploadHours.maxOrNull()?.coerceAtLeast(1) ?: 1
        Row(Modifier.fillMaxWidth().height(90.dp).padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            rep.uploadHours.forEachIndexed { h, c ->
                Box(Modifier.weight(1f).padding(horizontal = 1.dp).fillMaxHeight((c.toFloat() / maxH).coerceAtLeast(0.02f))
                    .background(if (h in rep.bestHours) Hot else Sky.copy(alpha = .6f), RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("12AM", "6AM", "12PM", "6PM", "11PM").forEach { Text(it, fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f)) }
        }
        Text("Tip: peak time se 1-2 ghante pehle upload karo taaki algorithm ko test karne ka time mile.", fontSize = 11.sp, color = Muted)

        SectionTitle("🔑 Trending keywords (titles me)")
        BarRow(rep.topKeywords.take(12), Hot)
        if (rep.topTags.isNotEmpty()) {
            SectionTitle("🏷 Trending tags")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rep.topTags.forEach { Chip("${it.first} (${it.second})") }
            }
        }

        SectionTitle("🎬 Format & length")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("Shorts share", "%.0f%%".format(rep.shortsShare * 100), Modifier.weight(1f))
            StatBox("Avg engagement", "%.1f%%".format(rep.avgEngagement * 100), Modifier.weight(1f))
            StatBox("Median age", Fmt.age(rep.medianAgeHours), Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        BarRow(rep.durationBuckets, Sky)

        SectionTitle("🧲 Viral title formula (trending me kitne % titles me)")
        Meter("Number (5, 100, ₹…)", rep.pctNumbers, Gold, "%.0f%%".format(rep.pctNumbers * 100))
        Meter("CAPS word", rep.pctCaps, Gold, "%.0f%%".format(rep.pctCaps * 100))
        Meter("Emoji", rep.pctEmoji, Gold, "%.0f%%".format(rep.pctEmoji * 100))
        Meter("#hashtag", rep.pctHashtag, Gold, "%.0f%%".format(rep.pctHashtag * 100))
        Meter("Question ?", rep.pctQuestion, Gold, "%.0f%%".format(rep.pctQuestion * 100))
        Text("Average title length: ${rep.titleAvgLen} characters", fontSize = 13.sp)

        SectionTitle("🏆 Channels with most trending videos")
        BarRow(rep.topChannels, Mint)
        Spacer(Modifier.height(24.dp))
    }
}

private fun hourLabel(h: Int) = when {
    h == 0 -> "12 AM"; h < 12 -> "$h AM"; h == 12 -> "12 PM"; else -> "${h - 12} PM"
}

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val p = vm.prefs
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(p.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var interval by remember { mutableIntStateOf(p.intervalMin) }
    var threshold by remember { mutableFloatStateOf(p.alertThreshold.toFloat()) }
    var niche by remember { mutableStateOf(p.nicheKeywords) }
    var nicheHours by remember { mutableIntStateOf(p.nicheHoursBack) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Settings", fontWeight = FontWeight.Black, fontSize = 22.sp)

        SectionTitle("🔑 YouTube API key (free)")
        Text(
            "1. console.cloud.google.com kholo → naya project banao\n" +
                "2. 'APIs & Services' → 'Enable APIs' → 'YouTube Data API v3' enable karo\n" +
                "3. 'Credentials' → 'Create credentials' → 'API key' → copy karke yahan paste karo\n" +
                "Free limit: 10,000 units/day (trending refresh = ~3 units, search = ~102 units).",
            fontSize = 12.sp, color = Muted,
        )
        TextButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.cloud.google.com/apis/library/youtube.googleapis.com"))) }) {
            Text("Google Cloud Console kholo →")
        }
        OutlinedTextField(
            key, { key = it; saved = false }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("API key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
        )

        SectionTitle("⏱ Background update interval")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15 to "15 min", 30 to "30 min", 60 to "1 ghanta", 180 to "3 ghante", 360 to "6 ghante").forEach { (m, l) ->
                FilterChip(interval == m, { interval = m; saved = false }, label = { Text(l) })
            }
        }
        Text("Tracked videos ke stats is interval pe save hote hain (1 unit har baar).", fontSize = 11.sp, color = Muted)

        SectionTitle("🤖 Auto topic scan (background)")
        var autoBg by remember { mutableStateOf(p.autoScanBg) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Har 12 ghante me khud low-competition topics dhoondo aur notification bhejo (≈${com.gk.viralradar.data.AutoScan.quotaEstimate(p.autoScanSize)} units/scan)",
                fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(autoBg, { autoBg = it; p.autoScanBg = it })
        }

        SectionTitle("🔔 Niche alerts")
        OutlinedTextField(niche, { niche = it; saved = false }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Keywords (comma se alag, max 5)") }, placeholder = { Text("prank, comedy shorts, family vlog") })
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(12 to "12h ke videos", 24 to "24h ke", 72 to "3 din ke").forEach { (h, l) ->
                FilterChip(nicheHours == h, { nicheHours = h; saved = false }, label = { Text(l) })
            }
        }
        Text("Alert jab score ≥ ${threshold.toInt()}", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Slider(threshold, { threshold = it; saved = false }, valueRange = 40f..95f)
        Text("Har 3 ghante me in keywords me naye breakout videos check honge aur notification aayega. (~102 units per keyword per check)",
            fontSize = 11.sp, color = Muted)

        Spacer(Modifier.height(16.dp))
        Button({
            vm.saveSettings(key, interval, threshold.toInt(), niche, nicheHours); saved = true
        }, Modifier.fillMaxWidth()) { Text(if (saved) "✅ Saved" else "Save") }

        SectionTitle("ℹ️ Viral Score kaise kaam karta hai")
        Text(
            "YouTube ka asli algorithm secret hai — koi bhi app usko andar se nahi dekh sakti. Ye app wahi public signals measure karti hai " +
                "jinpe algorithm chalta hai:\n\n" +
                "• Velocity (40%) – views per hour. Algorithm tez chalne wali video ko aur push karta hai.\n" +
                "• Outlier (25%) – views ÷ channel subscribers. Chhote channel pe bade views = algorithm browse/suggested me push kar raha hai.\n" +
                "• Engagement (20%) – (likes + 2×comments) ÷ views.\n" +
                "• Freshness (15%) – nayi video ko zyada weight.\n\n" +
                "Tracker real hourly snapshots se acceleration nikaalta hai — speed lagatar badh rahi ho to video viral hone ke raste pe hai. " +
                "Ye probability/estimate hai, guarantee nahi.",
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun NoKeyCard(onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📡", fontSize = 56.sp)
        Text("Viral Radar", fontWeight = FontWeight.Black, fontSize = 26.sp)
        Text("Live YouTube data ke liye ek free YouTube API key chahiye (2 minute ka kaam).", color = Muted,
            modifier = Modifier.padding(vertical = 12.dp))
        Button(onGo) { Text("Settings me key daalo") }
    }
}
