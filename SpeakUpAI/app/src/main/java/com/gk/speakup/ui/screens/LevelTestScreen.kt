package com.gk.speakup.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.speakup.LocalGraph
import com.gk.speakup.ai.ChatTurn
import com.gk.speakup.ai.Effort
import com.gk.speakup.ai.Prompts
import com.gk.speakup.data.Catalog
import com.gk.speakup.data.Store
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.MicButton
import com.gk.speakup.ui.SectionCard
import com.gk.speakup.ui.wordCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val selfRatings = listOf(
    "Mujhe English bolna bilkul nahi aata",
    "Thoda bahut bol leta/leti hoon, par atak jaata/jaati hoon",
    "Normal baat kar leta/leti hoon, par galtiyan hoti hain",
    "Achhi English bolta/bolti hoon, bas aur fluent hona hai",
)

@Composable
fun LevelTestScreen(onFinished: () -> Unit, onSkip: () -> Unit) {
    val graph = LocalGraph.current
    val speech = graph.speech
    val profile = graph.store.profile
    val persona = Catalog.persona(profile.personaId)
    val scope = rememberCoroutineScope()
    val questions = Prompts.levelQuestions

    var stage by remember { mutableStateOf("intro") }  // intro, self, questions, grading
    var selfRating by remember { mutableIntStateOf(-1) }
    var index by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateListOf(*Array(questions.size) { "" }) }
    val seconds = remember { mutableStateListOf(*Array(questions.size) { 0L }) }
    var error by remember { mutableStateOf<String?>(null) }

    fun speakQuestion() = speech.speak(questions[index], profile.accent, profile.speechRate)

    fun grade() {
        stage = "grading"
        error = null
        val transcript = buildString {
            append("Self-assessment: ").append(selfRatings.getOrNull(selfRating) ?: "not given").append("\n\n")
            questions.forEachIndexed { i, q ->
                val a = answers[i].trim()
                val wc = wordCount(a)
                val secs = seconds[i]
                val wpm = if (secs > 0 && wc > 0) (wc * 60 / secs) else 0
                append("Q${i + 1}: $q\n")
                append("Answer (${secs}s, $wc words, ~$wpm wpm): ${a.ifBlank { "[skipped]" }}\n\n")
            }
        }
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    graph.ai().askJson(
                        Prompts.levelSystem, listOf(ChatTurn(true, transcript)), Prompts.levelSchema,
                        effort = Effort.MEDIUM, maxTokens = 16000L,
                    )
                }
                graph.store.saveLevel(Store.levelFromJson(json))
                val totalSecs = seconds.sum()
                graph.store.addActivity(totalSecs, answers.sumOf { wordCount(it) }, xp = 50)
                onFinished()
            } catch (e: Exception) {
                error = e.message ?: "Kuch gadbad ho gayi"
                stage = "questions"
            }
        }
    }

    LaunchedEffect(stage, index) {
        if (stage == "questions") speakQuestion()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        when (stage) {
            "intro" -> {
                Gap(32.dp)
                Text(persona.emoji, fontSize = 64.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Gap()
                Text(
                    "Speaking Level Test", style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Gap()
                SectionCard {
                    Text("Hi ${profile.name}! Main ${persona.name} hoon.", fontWeight = FontWeight.Bold)
                    Gap(6.dp)
                    Text(
                        "Main aapse ${questions.size} sawaal poochungi/poochunga, aasaan se mushkil tak. " +
                            "Har sawaal ka jawab mic dabakar English me bolein. Jitna ho sake utna bolein - " +
                            "galtiyon ki chinta mat karein!\n\n" +
                            "Isse AI aapka level (A1-C2) pata karega aur phir usi hisaab se aapko sikhayega. " +
                            "Lagbhag 5-7 minute lagenge."
                    )
                }
                Gap(24.dp)
                Button(onClick = { stage = "self" }, modifier = Modifier.fillMaxWidth()) { Text("Start test") }
                TextButton(onClick = {
                    graph.store.updateProfile { it.copy(level = it.level.ifBlank { "A2" }) }
                    onSkip()
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Skip for now (start as Elementary)")
                }
            }

            "self" -> {
                Gap(24.dp)
                Text("Pehle ek chhota sa sawaal", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Aap apni spoken English ko kaisa maante hain?",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                )
                Gap(16.dp)
                selfRatings.forEachIndexed { i, label ->
                    val selected = i == selfRating
                    if (selected) {
                        Button(onClick = { selfRating = i }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(onClick = { selfRating = i }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(label)
                        }
                    }
                }
                Gap(20.dp)
                Button(
                    onClick = { stage = "questions" }, enabled = selfRating >= 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next") }
            }

            "questions" -> {
                LinearProgressIndicator(
                    progress = { (index + 1f) / questions.size },
                    modifier = Modifier.fillMaxWidth(),
                )
                Gap(8.dp)
                Text("Question ${index + 1} of ${questions.size}", style = MaterialTheme.typography.labelLarge)
                Gap()
                SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            questions[index], style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { speakQuestion() }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Listen again")
                        }
                    }
                }
                Gap()
                OutlinedTextField(
                    value = if (speech.isListening && speech.partial.isNotBlank())
                        (answers[index] + " " + speech.partial).trim() else answers[index],
                    onValueChange = { answers[index] = it },
                    label = { Text("Your answer (speak or type)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
                Gap(4.dp)
                Text(
                    if (speech.isListening) "Listening... bolte rahiye, ruk jaane par mic band ho jayega"
                    else "Mic dabakar bolein. Aur bolna ho to dobara dabayein - text jud jayega.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MicButton(listening = speech.isListening, level = speech.level) {
                        if (speech.isListening) speech.stopListening()
                        else {
                            error = null
                            val i = index
                            speech.listen(profile.accent, onResult = { text, secs ->
                                answers[i] = (answers[i] + " " + text).trim()
                                seconds[i] = seconds[i] + secs
                            }, onError = { error = it })
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Gap(8.dp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            speech.cancelListening()
                            if (index < questions.size - 1) index++ else grade()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip") }
                    Button(
                        onClick = {
                            speech.cancelListening()
                            if (seconds[index] == 0L) seconds[index] = (wordCount(answers[index]) / 2L).coerceAtLeast(1)
                            if (index < questions.size - 1) index++ else grade()
                        },
                        enabled = answers[index].isNotBlank() && !speech.isListening,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (index < questions.size - 1) "Next" else "Finish") }
                }
            }

            "grading" -> {
                Gap(120.dp)
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                Gap(20.dp)
                Text(
                    "AI aapke jawab check kar raha hai...", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    "Grammar, vocabulary, fluency aur pronunciation", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
