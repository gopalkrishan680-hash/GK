package com.gk.speakup.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.speakup.LocalGraph
import com.gk.speakup.data.LevelResult
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.HGap
import com.gk.speakup.ui.ScoreRing
import com.gk.speakup.ui.SectionCard
import com.gk.speakup.ui.SkillBar
import com.gk.speakup.ui.levelName

@Composable
fun LevelResultScreen(onContinue: () -> Unit) {
    val r = LocalGraph.current.store.levelResult
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        if (r == null) {
            Text("No result yet.")
        } else {
            LevelReport(r)
        }
        Gap(24.dp)
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Start practising", fontSize = 17.sp)
        }
    }
}

@Composable
fun LevelReport(r: LevelResult) {
    Gap(12.dp)
    Text("Your English level", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(r.level, fontSize = 64.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        HGap(16.dp)
        Column(Modifier.weight(1f)) {
            Text(levelName(r.level), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("CEFR scale A1 → C2", style = MaterialTheme.typography.bodySmall)
        }
        ScoreRing(r.overall, label = "overall")
    }
    Gap()
    SectionCard {
        SkillBar("Grammar", r.grammar)
        SkillBar("Vocabulary", r.vocabulary)
        SkillBar("Fluency", r.fluency)
        SkillBar("Pronunciation", r.pronunciation)
    }
    Gap()
    SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
        Text(r.summary)
        if (r.adviceHinglish.isNotBlank()) {
            Gap(8.dp)
            Text(r.adviceHinglish, fontWeight = FontWeight.Medium)
        }
    }
    Gap()
    ListCard("💪 Strengths", r.strengths)
    Gap()
    ListCard("🔧 Needs work", r.weaknesses)
    Gap()
    ListCard("🎯 Your focus areas", r.focusAreas)
}

@Composable
fun ListCard(title: String, items: List<String>) {
    if (items.isEmpty()) return
    SectionCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Gap(6.dp)
        items.forEach { Text("• $it", modifier = Modifier.padding(vertical = 2.dp)) }
    }
}
