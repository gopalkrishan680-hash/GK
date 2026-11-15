package com.gk.channelradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.channelradar.data.ScanConfig
import com.gk.channelradar.data.VideoFormat
import com.gk.channelradar.engine.Niches

private data class Preset(val name: String, val apply: (ScanConfig) -> ScanConfig)

private val presets = listOf(
    Preset("⚡ Quick") { it.copy(keywordsPerScan = 3, pagesPerKeyword = 1, includeTrending = true, deep = true) },
    Preset("⚖️ Balanced") { it.copy(keywordsPerScan = 8, pagesPerKeyword = 1, includeTrending = true, deep = true, days = 7) },
    Preset("🔭 Deep dive") { it.copy(keywordsPerScan = 15, pagesPerKeyword = 2, includeTrending = true, deep = true, days = 14) },
    Preset("📱 Shorts hunter") { it.copy(format = VideoFormat.SHORTS, minViews = 20_000, minRatio = 3.0, days = 7) },
    Preset("🌱 Tiny (<1K subs)") { it.copy(maxSubs = 1_000, minSubs = 0, minViews = 2_000, minRatio = 2.0) },
    Preset("🎬 Long-form gems") { it.copy(format = VideoFormat.LONG, maxSubs = 20_000, minViews = 5_000, minRatio = 1.0) },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(vm: AppViewModel) {
    val c = vm.config
    fun set(f: (ScanConfig) -> ScanConfig) = vm.updateConfig(f(c))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("Channel Radar", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Chote channels dhoondho jinke naye videos tez views le rahe hain – kisi bhi niche me.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp
        )

        if (vm.settings.apiKeys.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(
                onClick = { vm.tab = Tab.SETTINGS },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.padding(4.dp))
                    Text("Pehle Settings me apni FREE YouTube API key daalo (2 min ka kaam). Yahan tap karo →", fontSize = 13.sp)
                }
            }
        }

        // ------------------------------------------------ scan button / progress
        Spacer(Modifier.height(14.dp))
        if (vm.scanning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(vm.progressText, fontSize = 13.sp, maxLines = 2)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.cancelScan() }) { Text("Cancel") }
                }
            }
        } else {
            Button(
                onClick = { vm.startScan() }, enabled = vm.settings.apiKeys.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Filled.Search, null)
                Spacer(Modifier.padding(4.dp))
                Text("Scan for rising small channels", fontWeight = FontWeight.SemiBold)
            }
        }
        val est = vm.estimateQuota(c)
        val keys = vm.settings.apiKeys.size.coerceAtLeast(1)
        val left = 10_000 * keys - vm.quotaUsedToday()
        Text(
            "Is scan ka anumaanit kharcha: ~$est units · aaj bacha: ~${left.coerceAtLeast(0)} units (≈ ${(left / est.coerceAtLeast(1)).coerceAtLeast(0)} aur scans)",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
        )

        // ------------------------------------------------ presets
        SectionTitle("Presets")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.forEach { p -> AssistChip(onClick = { set(p.apply) }, label = { Text(p.name) }) }
        }

        // ------------------------------------------------ niches
        SectionTitle("Niches (khaali = sab niches, random mix)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = c.niches.isEmpty(), onClick = { set { it.copy(niches = emptyList()) } }, label = { Text("🌍 All") })
            Niches.all.forEach { n ->
                val sel = n.name in c.niches
                FilterChip(
                    selected = sel,
                    onClick = { set { it.copy(niches = if (sel) it.niches - n.name else it.niches + n.name) } },
                    label = { Text("${n.emoji} ${n.name}") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = c.customKeywords, onValueChange = { v -> set { it.copy(customKeywords = v) } },
            label = { Text("Apne keywords (comma se alag) – optional") },
            placeholder = { Text("e.g. ai video, desi kahani, minecraft") },
            modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3
        )

        SectionTitle("Keywords per scan: ${c.keywordsPerScan}  (har keyword = 100 units)")
        Slider(
            value = c.keywordsPerScan.toFloat(), onValueChange = { v -> set { it.copy(keywordsPerScan = v.toInt()) } },
            valueRange = 1f..20f, steps = 18
        )
        ChipRow(listOf(1 to "1 page / keyword", 2 to "2 pages (zyada results)"), c.pagesPerKeyword) { v -> set { it.copy(pagesPerKeyword = v) } }

        // ------------------------------------------------ filters
        SectionTitle("Video kitna naya ho")
        ChipRow(listOf(2 to "48 ghante", 3 to "3 din", 7 to "7 din", 14 to "14 din", 30 to "30 din"), c.days) { v -> set { it.copy(days = v) } }

        SectionTitle("Channel size (subscribers)")
        Text("Maximum", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(
            listOf(1_000L to "1K", 5_000L to "5K", 10_000L to "10K", 50_000L to "50K", 100_000L to "100K", 250_000L to "250K"),
            c.maxSubs
        ) { v -> set { it.copy(maxSubs = v) } }
        Text("Minimum", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(listOf(0L to "0", 100L to "100", 500L to "500", 1_000L to "1K", 5_000L to "5K"), c.minSubs) { v -> set { it.copy(minSubs = v) } }

        SectionTitle("Minimum views on the video")
        ChipRow(
            listOf(1_000L to "1K", 5_000L to "5K", 10_000L to "10K", 50_000L to "50K", 100_000L to "100K", 500_000L to "500K"),
            c.minViews
        ) { v -> set { it.copy(minViews = v) } }

        SectionTitle("Views ÷ Subscribers kam se kam")
        ChipRow(listOf(0.5 to "0.5x", 1.0 to "1x", 2.0 to "2x", 5.0 to "5x", 10.0 to "10x"), c.minRatio) { v -> set { it.copy(minRatio = v) } }

        SectionTitle("Format")
        ChipRow(
            listOf(VideoFormat.ANY to "Sab", VideoFormat.SHORTS to "📱 Shorts", VideoFormat.LONG to "🎬 Long videos"),
            c.format
        ) { v -> set { it.copy(format = v) } }

        SectionTitle("Region & language")
        ChipRow(
            listOf("IN" to "🇮🇳 India", "US" to "🇺🇸 US", "GB" to "🇬🇧 UK", "PK" to "🇵🇰 PK", "BD" to "🇧🇩 BD", "AE" to "🇦🇪 UAE", "CA" to "🇨🇦 CA"),
            c.region
        ) { v -> set { it.copy(region = v) } }
        Spacer(Modifier.height(4.dp))
        ChipRow(listOf("hi" to "Hindi", "en" to "English", "" to "Any language"), c.language) { v -> set { it.copy(language = v) } }

        SectionTitle("Advanced")
        ToggleRow("Trending charts bhi scan karo", "14 categories ke trending videos (sirf ~17 units)", c.includeTrending) { v -> set { it.copy(includeTrending = v) } }
        ToggleRow("Deep analysis (recommended)", "Har channel ke last 15 videos se compare karke 'outlier' video pakdo (~2 units/channel)", c.deep) { v -> set { it.copy(deep = v) } }

        Spacer(Modifier.height(30.dp))
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
