package com.gk.speakup.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.gk.speakup.LocalGraph
import com.gk.speakup.ai.ChatTurn
import com.gk.speakup.ai.Prompts
import com.gk.speakup.data.Catalog
import com.gk.speakup.data.strings
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.HGap
import com.gk.speakup.ui.Pill
import com.gk.speakup.ui.SectionCard
import com.gk.speakup.ui.levelName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen(nav: NavController) {
    val graph = LocalGraph.current
    val store = graph.store
    val profile = store.profile
    val persona = Catalog.persona(profile.personaId)
    val today = store.todayMinutes()
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    var daily by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(profile.level, profile.activeKey) {
        val cached = store.dailyCache()
        if (cached != null && cached.first == LocalDate.now().toEpochDay()) {
            daily = runCatching { JSONObject(cached.second) }.getOrNull()
        } else if (profile.activeKey.isNotBlank()) {
            daily = runCatching {
                withContext(Dispatchers.IO) {
                    graph.ai().askJson(
                        Prompts.dailySystem(profile),
                        listOf(ChatTurn(true, "Today's date is ${LocalDate.now()}. Give today's word.")),
                        Prompts.dailySchema, maxTokens = 4000L,
                    )
                }
            }.getOrNull()?.also { store.saveDaily(it.toString()) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$greeting,", style = MaterialTheme.typography.titleMedium)
                Text(profile.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("🔥 ${store.currentStreak()} day streak", MaterialTheme.colorScheme.tertiaryContainer)
            Pill("⭐ ${store.stats.xp} XP", MaterialTheme.colorScheme.secondaryContainer)
            Pill("${profile.level.ifBlank { "?" }} · ${levelName(profile.level)}")
        }
        Gap()

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's goal", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("$today / ${profile.dailyGoalMin} min")
            }
            Gap(8.dp)
            LinearProgressIndicator(
                progress = { (today.toFloat() / profile.dailyGoalMin).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            if (today >= profile.dailyGoalMin) {
                Gap(6.dp)
                Text("🎉 Goal complete! Shabaash!", style = MaterialTheme.typography.bodySmall)
            }
        }
        Gap()

        // Main call to action
        SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(persona.emoji, fontSize = 44.sp)
                HGap(12.dp)
                Column(Modifier.weight(1f)) {
                    Text("Talk with ${persona.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Live conversation · instant corrections", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Gap()
            Button(onClick = { nav.navigate("chat/free") }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("🎙️  Start speaking", fontSize = 17.sp)
            }
            Gap(6.dp)
            OutlinedButton(
                onClick = {
                    val topic = Catalog.dailyTopics[(LocalDate.now().toEpochDay() % Catalog.dailyTopics.size).toInt()]
                    nav.navigate("chat/" + Uri.encode("topic:$topic"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🗓️  Today's topic") }
        }
        Gap()

        daily?.let { d ->
            val word = d.optString("word")
            SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Word of the day", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { graph.speech.speak(word, profile.accent, profile.speechRate) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Listen")
                    }
                }
                Text(word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(d.optString("meaning"))
                d.optJSONArray("examples").strings().forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
                Gap(6.dp)
                Text("🎯 ${d.optString("task")}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                TextButton(onClick = { nav.navigate("chat/" + Uri.encode("word:$word")) }) { Text("Practise this word →") }
            }
            Gap()
        }

        Text("Practice", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Gap(8.dp)
        Tiles(
            listOf(
                Tile("🎭", "Role-plays", "Interview, travel, office...") { nav.navigate("scenarios") },
                Tile("🔊", "Pronunciation", "Speak & get word-level check") { nav.navigate("pronunciation") },
                Tile("🎯", "Mistake drill", "${store.mistakes.size} mistakes to fix") { nav.navigate("chat/drill") },
                Tile("📘", "Vocabulary", "${store.vocab.size} words saved") { nav.navigate("vocab") },
                Tile("📝", "Mistake journal", "Review your corrections") { nav.navigate("mistakes") },
                Tile("📊", "Progress", "Level, scores & history") { nav.navigate("progress") },
            )
        )
        Gap(24.dp)
    }
}

private data class Tile(val emoji: String, val title: String, val sub: String, val onClick: () -> Unit)

@Composable
private fun Tiles(tiles: List<Tile>) {
    tiles.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t ->
                SectionCard(modifier = Modifier.weight(1f).clickable { t.onClick() }) {
                    Text(t.emoji, fontSize = 28.sp)
                    Gap(4.dp)
                    Text(t.title, fontWeight = FontWeight.Bold)
                    Text(t.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
            if (row.size == 1) Column(Modifier.weight(1f)) {}
        }
        Gap(10.dp)
    }
}
