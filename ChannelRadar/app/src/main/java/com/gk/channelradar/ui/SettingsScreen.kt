package com.gk.channelradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var keysText by rememberSaveable { mutableStateOf(vm.settings.apiKeys.joinToString("\n")) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshQuota() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        PageHeader("Settings", "API keys, quota and how the app works.", "⚙️")

        SectionTitle("YouTube Data API key(s)")
        Text(
            "The app gets data straight from YouTube's official FREE API – no vidIQ or paid tools. " +
                "Each key gets 10,000 free units per day. Add more than one key and the app switches to the next one automatically when a key runs out.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keysText, onValueChange = { keysText = it },
            label = { Text("API keys (one per line)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
            visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
        )
        Row {
            Button(onClick = { vm.saveKeys(keysText) }) { Text("Save keys") }
            TextButton(onClick = { showKeys = !showKeys }) { Text(if (showKeys) "Hide" else "Show") }
        }

        SectionTitle("Today's quota (resets at midnight Pacific time)")
        val keys = vm.settings.apiKeys
        if (keys.isEmpty()) Text("No keys added yet.", fontSize = 12.sp)
        keys.forEach { k ->
            val label = com.gk.channelradar.data.Store.keyLabel(k)
            val used = vm.quota.used[label] ?: 0
            val ex = label in vm.quota.exhausted
            Text("Key $label: $used / 10,000" + if (ex) "  (used up)" else "", fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { if (ex) 1f else (used / 10_000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        SectionTitle("How to get a free API key (one time, ~2 minutes)")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                listOf(
                    "1. Open console.cloud.google.com with your Google account.",
                    "2. Top bar: 'Select project' → 'New project' → any name → Create.",
                    "3. 'APIs & Services' → 'Library' → search \"YouTube Data API v3\" → Enable.",
                    "4. 'APIs & Services' → 'Credentials' → '+ Create credentials' → 'API key'.",
                    "5. Copy the key, paste it above and tap Save. (Never share your key.)",
                    "Tip: create 2–3 keys in different projects → 2–3x more scans per day.",
                    "No credit card needed. If you restrict the key, restrict it only to 'YouTube Data API v3' – don't add an Android-app restriction.",
                ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openUrl(ctx, "https://console.cloud.google.com/apis/library/youtube.googleapis.com") }) {
                    Text("Open Google Cloud Console")
                }
            }
        }

        SectionTitle("How the app finds rising channels")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                listOf(
                    "① Searches YouTube across many niche keywords (videos from the last N days, most viewed first) + trending charts of 14 categories.",
                    "② Fetches the real stats of every video and channel.",
                    "③ Keeps only SMALL channels (the subscriber range you chose).",
                    "④ Scores each video: Views ÷ Subscribers (how far beyond its own audience it went), Views per hour (speed) and Engagement (likes + comments).",
                    "⑤ Deep analysis: compares the video with the median of the channel's last 15 uploads (Outlier ×), and also finds other hits and consistency.",
                    "⑥ Breakout Score (0–100) = 35% views÷subs + 25% outlier + 25% speed + 15% engagement, plus a bonus for multiple hits and consistency.",
                    "🆕 NEW = this channel never appeared in any of your earlier scans.",
                ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }

        SectionTitle("Trends & demand")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                listOf(
                    "🔎 YouTube tab: explores YouTube's live search suggestions. Phrases that appear in many suggestion lists, near the top, are what people search most right now. Free.",
                    "📈 Google tab: today's fastest-rising Google searches for your country, with approximate search volume. Free.",
                    "🎯 Demand check: looks at the top videos of the last 30 days for a keyword. Opportunity = 35% demand (typical views) + 40% how often small channels beat their subscriber count + 25% freshness.",
                ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }

        SectionTitle("Data")
        Text("Hidden channels: ${vm.hidden.size}", fontSize = 13.sp)
        Row {
            OutlinedButton(onClick = { vm.clearHidden() }, enabled = vm.hidden.isNotEmpty()) { Text("Unhide all") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { vm.clearCache() }) { Text("Clear results") }
        }
        Spacer(Modifier.height(30.dp))
    }
}
