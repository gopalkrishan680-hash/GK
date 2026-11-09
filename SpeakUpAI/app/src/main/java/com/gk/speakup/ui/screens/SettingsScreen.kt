package com.gk.speakup.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gk.speakup.LocalGraph
import com.gk.speakup.ai.ClaudeService
import com.gk.speakup.data.Catalog
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val graph = LocalGraph.current
    val store = graph.store
    val p = store.profile
    var name by remember { mutableStateOf(p.name) }
    var goal by remember { mutableStateOf(p.goal) }
    var key by remember { mutableStateOf(p.apiKey) }
    var gKey by remember { mutableStateOf(p.geminiKey) }
    var confirmReset by remember { mutableStateOf(false) }

    fun save() = store.updateProfile { it.copy(name = name.trim(), goal = goal.trim(), apiKey = key.trim(), geminiKey = gKey.trim()) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { save(); nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionCard {
                Header("Profile")
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Gap(8.dp)
                OutlinedTextField(goal, { goal = it }, label = { Text("Your goal") }, modifier = Modifier.fillMaxWidth())
                Gap(8.dp)
                Text("Level: ${p.level.ifBlank { "not tested" }}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("A1", "A2", "B1", "B2", "C1", "C2").forEach { l ->
                        FilterChip(selected = p.level == l, onClick = { store.updateProfile { it.copy(level = l) } }, label = { Text(l) })
                    }
                }
                TextButton(onClick = { save(); nav.navigate("levelTest") }) { Text("Retake level test") }
            }
            Gap()
            SectionCard {
                Header("AI coach")
                Catalog.personas.forEach { persona ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = p.personaId == persona.id, onClick = {
                            store.updateProfile { it.copy(personaId = persona.id, accent = persona.accent) }
                        })
                        Text("${persona.emoji} ${persona.name} - ${persona.tagline}")
                    }
                }
                Gap(8.dp)
                Text("Voice accent", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("en-US" to "🇺🇸 US", "en-GB" to "🇬🇧 UK", "en-IN" to "🇮🇳 India").forEach { (tag, label) ->
                        FilterChip(selected = p.accent == tag, onClick = { store.updateProfile { it.copy(accent = tag) } }, label = { Text(label) })
                    }
                }
                Gap(8.dp)
                Text("Speaking speed: ${"%.2f".format(p.speechRate)}x", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = p.speechRate, onValueChange = { v -> store.updateProfile { it.copy(speechRate = v) } },
                    valueRange = 0.5f..1.4f,
                    onValueChangeFinished = { graph.speech.speak("This is how fast I will speak.", p.accent, p.speechRate) },
                )
                SwitchRow("Explain mistakes in Hinglish", p.hinglish) { v -> store.updateProfile { it.copy(hinglish = v) } }
                SwitchRow("Hands-free mode (auto listen after coach speaks)", p.handsFree) { v -> store.updateProfile { it.copy(handsFree = v) } }
                Gap(8.dp)
                Text("Daily goal: ${p.dailyGoalMin} min", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { m ->
                        FilterChip(selected = p.dailyGoalMin == m, onClick = { store.updateProfile { it.copy(dailyGoalMin = m) } }, label = { Text("$m") })
                    }
                }
            }
            Gap()
            SectionCard {
                Header("AI provider & API key")
                ModelRow("gemini", "Google Gemini - FREE", p.provider) {
                    save(); store.updateProfile { it.copy(provider = "gemini") }
                }
                ModelRow("claude", "Claude (Anthropic) - paid, best corrections", p.provider) {
                    save(); store.updateProfile { it.copy(provider = "claude") }
                }
                Gap(8.dp)
                if (p.usesGemini) {
                    OutlinedTextField(
                        gKey, { gKey = it.trim() }, label = { Text("Gemini API key (free)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Model: " + p.geminiModel.ifBlank { "auto (best free Flash model)" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Free key: aistudio.google.com → Get API key. Free limit poori ho jaye to thoda ruk kar ya agle din try karein.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    ModelRow(ClaudeService.MODEL_BEST, "Claude Opus 5.5 - best corrections", p.model) {
                        store.updateProfile { it.copy(model = ClaudeService.MODEL_BEST) }
                    }
                    ModelRow(ClaudeService.MODEL_FAST, "Claude Haiku 5.5 - faster, much cheaper", p.model) {
                        store.updateProfile { it.copy(model = ClaudeService.MODEL_FAST) }
                    }
                    Gap(8.dp)
                    OutlinedTextField(
                        key, { key = it.trim() }, label = { Text("Anthropic API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Gap(8.dp)
                Button(onClick = { save() }) { Text("Save") }
            }
            Gap()
            OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Reset all data", color = MaterialTheme.colorScheme.error)
            }
            Gap(24.dp)
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset everything?") },
            text = { Text("Profile, progress, vocab, mistakes - sab delete ho jayega.") },
            confirmButton = {
                Button(onClick = {
                    confirmReset = false
                    store.resetAll()
                    nav.navigate("onboarding") { popUpTo(0) }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Gap(8.dp)
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun ModelRow(id: String, label: String, current: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = current == id, onClick = onClick)
        Text(label)
    }
}
