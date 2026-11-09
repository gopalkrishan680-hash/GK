package com.gk.speakup.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gk.speakup.LocalGraph
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.SectionCard
import com.gk.speakup.ui.theme.scoreColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(nav: NavController) {
    val store = LocalGraph.current.store
    val s = store.stats
    val fmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Your progress") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("🔥", "${store.currentStreak()}", "day streak", Modifier.weight(1f))
                Stat("⭐", "${s.xp}", "XP", Modifier.weight(1f))
                Stat("⏱️", "${s.seconds / 60}", "minutes", Modifier.weight(1f))
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("💬", "${s.words}", "words spoken", Modifier.weight(1f))
                Stat("🎓", "${s.sessions}", "sessions", Modifier.weight(1f))
                Stat("📘", "${store.vocab.size}", "words saved", Modifier.weight(1f))
            }
            Gap()

            val scores = store.sessions.filter { it.score > 0 }.take(15).reversed().map { it.score }
            if (scores.size >= 2) {
                SectionCard {
                    Text("Session scores", fontWeight = FontWeight.Bold)
                    Gap(8.dp)
                    val color = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                        val stepX = size.width / (scores.size - 1)
                        val path = Path()
                        scores.forEachIndexed { i, v ->
                            val p = Offset(i * stepX, size.height * (1 - v / 100f))
                            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                        }
                        drawPath(path, color, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                        scores.forEachIndexed { i, v ->
                            drawCircle(scoreColor(v), 5.dp.toPx(), Offset(i * stepX, size.height * (1 - v / 100f)))
                        }
                    }
                }
                Gap()
            }

            store.levelResult?.let { r ->
                SectionCard {
                    Text("Level test result", fontWeight = FontWeight.Bold)
                    Gap(4.dp)
                    LevelReport(r)
                }
                Gap()
            }
            OutlinedButton(onClick = { nav.navigate("levelTest") }, modifier = Modifier.fillMaxWidth()) {
                Text("Retake level test")
            }
            Gap()

            if (store.sessions.isNotEmpty()) {
                Text("Recent sessions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Gap(8.dp)
                store.sessions.take(20).forEach { r ->
                    SectionCard(Modifier.padding(vertical = 4.dp)) {
                        Row {
                            Text(r.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (r.score > 0) Text("${r.score}", color = scoreColor(r.score), fontWeight = FontWeight.Bold)
                        }
                        Text("${fmt.format(Date(r.at))} · ${r.minutes} min", style = MaterialTheme.typography.bodySmall)
                        Text(r.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(emoji: String, value: String, label: String, modifier: Modifier) {
    SectionCard(modifier) {
        Text(emoji)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
