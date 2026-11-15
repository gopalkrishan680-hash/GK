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
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        SectionTitle("YouTube Data API key(s)")
        Text(
            "App seedha YouTube ke official, FREE API se data leta hai – koi vidIQ / paid tool nahi. " +
                "Har key ko roz 10,000 free units milte hain. Ek se zyada key daaloge to quota khatam hone par app apne aap agli key use karega.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keysText, onValueChange = { keysText = it },
            label = { Text("API keys (har line me ek)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
            visualTransformation = if (showKeys) VisualTransformation.None else PasswordVisualTransformation(),
        )
        Row {
            Button(onClick = { vm.saveKeys(keysText) }) { Text("Save keys") }
            TextButton(onClick = { showKeys = !showKeys }) { Text(if (showKeys) "Hide" else "Show") }
        }

        SectionTitle("Aaj ka quota (Pacific time ke hisaab se reset)")
        val keys = vm.settings.apiKeys
        if (keys.isEmpty()) Text("Koi key nahi.", fontSize = 12.sp)
        keys.forEach { k ->
            val label = com.gk.channelradar.data.Store.keyLabel(k)
            val used = vm.quota.used[label] ?: 0
            val ex = label in vm.quota.exhausted
            Text("Key $label: $used / 10,000" + if (ex) "  (khatam)" else "", fontSize = 13.sp)
            LinearProgressIndicator(
                progress = { if (ex) 1f else (used / 10_000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
        }

        SectionTitle("Free API key kaise banaye (ek baar, ~2 minute)")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                listOf(
                    "1. Google account se console.cloud.google.com kholo.",
                    "2. Upar 'Select project' → 'New project' → koi bhi naam → Create.",
                    "3. 'APIs & Services' → 'Library' → \"YouTube Data API v3\" search karo → Enable.",
                    "4. 'APIs & Services' → 'Credentials' → '+ Create credentials' → 'API key'.",
                    "5. Key copy karke upar paste karo aur Save dabao. (Key ko kisi ke saath share mat karna.)",
                    "Tip: Alag-alag projects me 2-3 keys bana lo → roz 2-3x zyada scans.",
                    "Credit card ki zaroorat nahi. Agar key restrict karni ho to sirf 'YouTube Data API v3' tak restrict karna – Android-app restriction mat lagana.",
                ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { openUrl(ctx, "https://console.cloud.google.com/apis/library/youtube.googleapis.com") }) {
                    Text("Google Cloud Console kholo")
                }
            }
        }

        SectionTitle("App ka process kaise kaam karta hai")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                listOf(
                    "① Bahut saare niches ke keywords par YouTube search (pichhle N din ke videos, sabse zyada views wale pehle) + 14 categories ke trending charts.",
                    "② Har video aur channel ke asli stats laata hai.",
                    "③ Sirf CHOTE channels rakhta hai (jitne subscribers tumne chune).",
                    "④ Har video ko score karta hai: Views ÷ Subscribers (channel apni audience se kitna aage gaya), Views/hour (speed), Engagement (likes+comments).",
                    "⑤ Deep analysis: channel ke last 15 videos ka median nikal kar dekhta hai ki ye video normal se kitna guna zyada chala (Outlier ×). Saath hi channel ke doosre hits aur consistency bhi.",
                    "⑥ Breakout Score (0-100) = 35% views÷subs + 25% outlier + 25% speed + 15% engagement, + bonus multiple hits/consistency ke liye.",
                    "🆕 NEW tag = ye channel pehle kisi scan me nahi mila tha.",
                ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }

        SectionTitle("Data")
        Text("Hidden channels: ${vm.hidden.size}", fontSize = 13.sp)
        Row {
            OutlinedButton(onClick = { vm.clearHidden() }, enabled = vm.hidden.isNotEmpty()) { Text("Unhide all") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { vm.clearCache() }) { Text("Clear results & NEW history") }
        }
        Spacer(Modifier.height(30.dp))
    }
}
