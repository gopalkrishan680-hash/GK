package com.gk.channelradar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.channelradar.data.ScanConfig
import com.gk.channelradar.data.VideoFormat
import com.gk.channelradar.engine.Niches

private data class ScanSize(val emoji: String, val name: String, val keywords: Int, val pages: Int, val days: Int)

private val sizes = listOf(
    ScanSize("⚡", "Quick", 3, 1, 7),
    ScanSize("⚖️", "Balanced", 8, 1, 7),
    ScanSize("🔭", "Deep", 15, 2, 14),
)

private data class Preset(val name: String, val apply: (ScanConfig) -> ScanConfig)

private val presets = listOf(
    Preset("📱 Shorts hunter") { it.copy(format = VideoFormat.SHORTS, minViews = 20_000, minRatio = 3.0, days = 7) },
    Preset("🌱 Tiny channels") { it.copy(maxSubs = 1_000, minSubs = 0, minViews = 2_000, minRatio = 2.0) },
    Preset("🎬 Long-form gems") { it.copy(format = VideoFormat.LONG, maxSubs = 20_000, minViews = 5_000, minRatio = 1.0) },
    Preset("🚀 Viral only") { it.copy(minRatio = 10.0, minViews = 50_000, days = 7) },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val c = vm.config
    fun set(f: (ScanConfig) -> ScanConfig) = vm.updateConfig(f(c))
    var moreFilters by rememberSaveable { mutableStateOf(false) }
    var allNiches by rememberSaveable { mutableStateOf(false) }

    val est = vm.estimateQuota(c)
    val keys = vm.settings.apiKeys.size.coerceAtLeast(1)
    val left = (10_000 * keys - vm.quotaUsedToday()).coerceAtLeast(0)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        PageHeader("Channel Radar", "Find small channels whose new videos are blowing up, in any niche.", "📡") {
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtCount(left.toLong()), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                Text("units left", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
            }
        }

        if (vm.settings.apiKeys.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            SoftCard(selected = true) {
                Column(Modifier.padding(16.dp)) {
                    Text("👋 Welcome! One quick setup step", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Channel Radar uses YouTube's own free API. Add your free API key once and you're ready to scan.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { vm.tab = Tab.SETTINGS }) { Text("Add API key") }
                }
            }
        }

        // ------------------------------------------------ 1. scan size
        StepTitle("1", "Choose scan size")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sizes.forEach { s ->
                val sel = c.keywordsPerScan == s.keywords && c.pagesPerKeyword == s.pages
                val cost = vm.estimateQuota(c.copy(keywordsPerScan = s.keywords, pagesPerKeyword = s.pages))
                SoftCard(
                    modifier = Modifier.weight(1f), selected = sel,
                    onClick = { set { it.copy(keywordsPerScan = s.keywords, pagesPerKeyword = s.pages, days = s.days) } }
                ) {
                    Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.emoji, fontSize = 24.sp)
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("~${fmtCount(cost.toLong())} units", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // ------------------------------------------------ 2. what to find
        StepTitle("2", "What are you looking for?")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val opts = listOf(VideoFormat.ANY to "All videos", VideoFormat.SHORTS to "Shorts", VideoFormat.LONG to "Long videos")
            opts.forEachIndexed { i, (f, label) ->
                SegmentedButton(
                    selected = c.format == f, onClick = { set { it.copy(format = f) } },
                    shape = SegmentedButtonDefaults.itemShape(i, opts.size)
                ) { Text(label, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Channel size", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                1_000L to "🌱 Tiny\n< 1K subs", 10_000L to "🌿 Small\n< 10K subs",
                50_000L to "🌳 Growing\n< 50K subs", 250_000L to "🏔️ Mid-size\n< 250K subs",
            ).forEach { (v, label) ->
                SoftCard(selected = c.maxSubs == v, onClick = { set { it.copy(maxSubs = v) } }) {
                    Text(label, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }

        // ------------------------------------------------ 3. niches
        StepTitle("3", "Pick niches")
        if (c.customKeywords.isNotBlank()) {
            SoftCard(selected = true, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🎯 Your keywords", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(c.customKeywords, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    }
                    IconButton(onClick = { vm.clearScanKeywords() }) { Icon(Icons.Filled.Clear, "Clear keywords") }
                }
            }
            Text(
                if (c.niches.isEmpty()) "Only your keywords will be scanned. Pick niches to mix them in."
                else "Your keywords + the niches below will be scanned.",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        val shown = if (allNiches) Niches.all else Niches.all.take(10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = c.niches.isEmpty(), onClick = { set { it.copy(niches = emptyList()) } }, label = { Text("🌍 Any niche") })
            shown.forEach { n ->
                val sel = n.name in c.niches
                FilterChip(
                    selected = sel,
                    onClick = { set { it.copy(niches = if (sel) it.niches - n.name else it.niches + n.name) } },
                    label = { Text("${n.emoji} ${n.name}") }
                )
            }
            AssistChip(onClick = { allNiches = !allNiches }, label = { Text(if (allNiches) "Show less" else "+${Niches.all.size - 10} more") })
        }

        // ------------------------------------------------ scan button
        Spacer(Modifier.height(18.dp))
        if (vm.scanning) {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("🛰️ Scanning…", fontWeight = FontWeight.Bold)
                    Text(vm.progressText, fontSize = 13.sp, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { vm.cancelScan() }) { Text("Cancel") }
                }
            }
        } else {
            val enabled = vm.settings.apiKeys.isNotEmpty()
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (enabled) BrandGradient else androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surfaceVariant))
                    .clickable(enabled = enabled) { vm.startScan() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Find rising channels", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }
        Text(
            "This scan uses ~$est of your $left units left today (≈ ${(left / est.coerceAtLeast(1))} scans).",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth()
        )

        // ------------------------------------------------ smart presets
        SectionTitle("Smart presets")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p -> AssistChip(onClick = { set(p.apply) }, label = { Text(p.name) }) }
        }

        // ------------------------------------------------ more filters
        Spacer(Modifier.height(10.dp))
        SoftCard(modifier = Modifier.fillMaxWidth(), onClick = { moreFilters = !moreFilters }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("⚙️ More filters", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Last ${c.days}d · min ${fmtCount(c.minViews)} views · ${fmtX(c.minRatio)} subs · ${c.region} · ${c.language.ifBlank { "any" }}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(if (moreFilters) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
            }
        }
        AnimatedVisibility(moreFilters) {
            Column {
                SectionTitle("Video uploaded within")
                ChipRow(listOf(2 to "48 hours", 3 to "3 days", 7 to "7 days", 14 to "14 days", 30 to "30 days"), c.days) { v -> set { it.copy(days = v) } }

                SectionTitle("Minimum views on the video")
                ChipRow(
                    listOf(1_000L to "1K", 5_000L to "5K", 10_000L to "10K", 50_000L to "50K", 100_000L to "100K", 500_000L to "500K"),
                    c.minViews
                ) { v -> set { it.copy(minViews = v) } }

                SectionTitle("Views must be at least … × subscribers")
                ChipRow(listOf(0.5 to "0.5x", 1.0 to "1x", 2.0 to "2x", 5.0 to "5x", 10.0 to "10x"), c.minRatio) { v -> set { it.copy(minRatio = v) } }

                SectionTitle("Exact max subscribers")
                ChipRow(
                    listOf(1_000L to "1K", 5_000L to "5K", 10_000L to "10K", 20_000L to "20K", 50_000L to "50K", 100_000L to "100K", 250_000L to "250K"),
                    c.maxSubs
                ) { v -> set { it.copy(maxSubs = v) } }
                SectionTitle("Minimum subscribers")
                ChipRow(listOf(0L to "0", 100L to "100", 500L to "500", 1_000L to "1K", 5_000L to "5K"), c.minSubs) { v -> set { it.copy(minSubs = v) } }

                SectionTitle("Country")
                ChipRow(
                    listOf("IN" to "🇮🇳 India", "US" to "🇺🇸 US", "GB" to "🇬🇧 UK", "PK" to "🇵🇰 Pakistan", "BD" to "🇧🇩 Bangladesh", "AE" to "🇦🇪 UAE", "CA" to "🇨🇦 Canada"),
                    c.region
                ) { v -> set { it.copy(region = v) } }
                SectionTitle("Video language")
                ChipRow(listOf("hi" to "Hindi", "en" to "English", "" to "Any"), c.language) { v -> set { it.copy(language = v) } }

                SectionTitle("Custom keywords (comma separated)")
                OutlinedTextField(
                    value = c.customKeywords, onValueChange = { v -> set { it.copy(customKeywords = v) } },
                    placeholder = { Text("e.g. ai video, horror story, minecraft") },
                    modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3, shape = RoundedCornerShape(14.dp)
                )

                SectionTitle("Keywords per scan: ${c.keywordsPerScan} (100 units each)")
                Slider(
                    value = c.keywordsPerScan.toFloat(), onValueChange = { v -> set { it.copy(keywordsPerScan = v.toInt()) } },
                    valueRange = 1f..20f, steps = 18
                )
                ChipRow(listOf(1 to "1 page per keyword", 2 to "2 pages (more results)"), c.pagesPerKeyword) { v -> set { it.copy(pagesPerKeyword = v) } }

                Spacer(Modifier.height(6.dp))
                ToggleRow("Include trending charts", "Also checks trending videos in 14 categories (~17 units)", c.includeTrending) { v -> set { it.copy(includeTrending = v) } }
                ToggleRow("Deep analysis (recommended)", "Compares each hit with the channel's last 15 videos to spot true outliers (~2 units per channel)", c.deep) { v -> set { it.copy(deep = v) } }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun StepTitle(n: String, text: String) {
    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(BrandGradient).padding(horizontal = 9.dp, vertical = 2.dp)
        ) { Text(n, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Spacer(Modifier.width(8.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
