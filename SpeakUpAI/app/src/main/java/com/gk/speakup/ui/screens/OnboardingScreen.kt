package com.gk.speakup.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.speakup.LocalGraph
import com.gk.speakup.data.Catalog
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.HGap
import com.gk.speakup.ui.SectionCard

private val goals = listOf(
    "Job interviews", "Office & meetings", "Daily conversation", "Travel abroad",
    "IELTS / exams", "Confidence & fluency", "Business calls", "College / studies",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(graph.store.profile.name) }
    var selectedGoals by remember { mutableStateOf(setOf<String>()) }
    var apiKey by remember { mutableStateOf(graph.store.profile.apiKey) }
    var persona by remember { mutableStateOf(graph.store.profile.personaId) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        when (step) {
            0 -> {
                Gap(40.dp)
                Text("🗣️", fontSize = 72.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Gap()
                Text(
                    "SpeakUp AI", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    "Your personal AI English speaking coach",
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Gap(28.dp)
                Feature("🎙️", "Bolo, aur AI real-time me sunega", "Talk out loud with a friendly AI character, just like a real conversation.")
                Feature("✍️", "Har galti turant sudhregi", "Every mistake is corrected instantly, with a simple Hinglish explanation.")
                Feature("📊", "Aapke level ke hisaab se", "A quick speaking test finds your level (A1 to C2) and the AI adapts to you.")
                Feature("🎭", "Real-life role-plays", "Job interviews, restaurants, airports, office meetings, IELTS and more.")
                Feature("🔤", "Pronunciation, vocab & progress", "Pronunciation drills, a word notebook, mistake journal, streaks and XP.")
                Gap(24.dp)
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Let's start", fontSize = 17.sp)
                }
            }

            1 -> {
                Title("Aapka naam kya hai?", "What should your coach call you?")
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Your name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Gap(24.dp)
                Text("Why do you want to improve your English?", style = MaterialTheme.typography.titleMedium)
                Gap(8.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    goals.forEach { g ->
                        FilterChip(
                            selected = g in selectedGoals,
                            onClick = { selectedGoals = if (g in selectedGoals) selectedGoals - g else selectedGoals + g },
                            label = { Text(g) },
                        )
                    }
                }
                Gap(24.dp)
                Button(
                    onClick = { step = 2 }, enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Next") }
            }

            2 -> {
                Title("Choose your AI coach", "Aap kis ke saath practice karna chahenge?")
                Catalog.personas.forEach { p ->
                    val selected = p.id == persona
                    SectionCard(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .then(
                                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                                else Modifier
                            )
                            .clickable { persona = p.id },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.emoji, fontSize = 36.sp)
                            HGap(14.dp)
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(p.tagline, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Gap(20.dp)
                Button(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Next") }
            }

            3 -> {
                Title("Connect the AI", "The coach runs on Claude by Anthropic.")
                SectionCard(container = MaterialTheme.colorScheme.secondaryContainer) {
                    Text("API key kaise milegi:", fontWeight = FontWeight.Bold)
                    Gap(6.dp)
                    Text("1. console.anthropic.com par sign up karein\n2. Billing me thode credits add karein\n3. 'API Keys' me jaakar 'Create Key' dabayein\n4. Key copy karke yahan paste karein")
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
                    }) { Text("Open console.anthropic.com") }
                }
                Gap(16.dp)
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it.trim() },
                    label = { Text("Anthropic API key (sk-ant-...)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Gap(6.dp)
                Text(
                    "Key sirf aapke phone me save hoti hai aur seedha Anthropic ko jaati hai.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Gap(24.dp)
                Button(
                    onClick = {
                        graph.store.updateProfile {
                            it.copy(
                                name = name.trim(),
                                goal = selectedGoals.joinToString(", "),
                                apiKey = apiKey.trim(),
                                personaId = persona,
                                accent = Catalog.persona(persona).accent,
                            )
                        }
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        onDone()
                    },
                    enabled = apiKey.length > 20,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Start my level test") }
            }
        }
        if (step > 0) {
            TextButton(onClick = { step-- }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Back") }
        }
    }
}

@Composable
private fun Title(title: String, sub: String) {
    Gap(24.dp)
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Gap(20.dp)
}

@Composable
private fun Feature(emoji: String, title: String, body: String) {
    Row(Modifier.padding(vertical = 8.dp)) {
        Text(emoji, fontSize = 28.sp)
        HGap(14.dp)
        Column {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
