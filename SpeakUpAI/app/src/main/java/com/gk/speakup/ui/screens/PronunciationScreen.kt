package com.gk.speakup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.speakup.LocalGraph
import com.gk.speakup.ai.ChatTurn
import com.gk.speakup.ai.Prompts
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.MicButton
import com.gk.speakup.ui.ScoreRing
import com.gk.speakup.ui.SectionCard
import com.gk.speakup.ui.theme.Bad
import com.gk.speakup.ui.theme.Good
import com.gk.speakup.ui.wordCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PracticeSentence(val text: String, val focus: String, val tip: String)
private data class WordResult(val word: String, val ok: Boolean)

/** Word-level comparison of what the learner said against the target (LCS alignment). */
private fun compare(target: String, spoken: String): List<WordResult> {
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
    val displayWords = target.split(Regex("\\s+")).filter { it.isNotBlank() }
    val t = displayWords.map { norm(it).joinToString("") }
    val s = norm(spoken)
    val dp = Array(t.size + 1) { IntArray(s.size + 1) }
    for (i in t.indices.reversed()) for (j in s.indices.reversed()) {
        dp[i][j] = if (t[i] == s[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    }
    val ok = BooleanArray(t.size)
    var i = 0; var j = 0
    while (i < t.size && j < s.size) {
        when {
            t[i] == s[j] -> { ok[i] = true; i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
    }
    return displayWords.mapIndexed { k, w -> WordResult(w, ok[k] || t[k].isEmpty()) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PronunciationScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val speech = graph.speech
    val profile = graph.store.profile
    val scope = rememberCoroutineScope()

    var sentences by remember { mutableStateOf<List<PracticeSentence>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var spoken by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<WordResult>?>(null) }
    var totalScore by remember { mutableIntStateOf(0) }
    var attempts by remember { mutableIntStateOf(0) }

    fun load() {
        loading = true; error = null
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    graph.ai().askJson(
                        Prompts.pronunciationSystem(profile),
                        listOf(ChatTurn(true, "Create 6 practice sentences. Random seed: ${System.currentTimeMillis() % 9973}")),
                        Prompts.pronunciationSchema,
                    )
                }
                val a = json.getJSONArray("sentences")
                sentences = (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    PracticeSentence(o.optString("text"), o.optString("focus"), o.optString("tip"))
                }
                index = 0; spoken = ""; result = null
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { if (sentences.isEmpty()) load() }
    DisposableEffect(Unit) { onDispose { speech.stopSpeaking(); speech.cancelListening() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Pronunciation") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            if (loading) {
                Gap(80.dp)
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                Gap()
                Text("Aapke level ke sentences ban rahe hain...", modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                Button(onClick = { load() }) { Text("Try again") }
                return@Column
            }
            val s = sentences.getOrNull(index) ?: return@Column

            LinearProgressIndicator(progress = { (index + 1f) / sentences.size }, modifier = Modifier.fillMaxWidth())
            Gap(6.dp)
            Text("Sentence ${index + 1} of ${sentences.size} · Focus: ${s.focus}", style = MaterialTheme.typography.labelLarge)
            Gap()
            SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text(s.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { speech.speak(s.text, profile.accent, profile.speechRate) }) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null); Text(" Listen")
                    }
                    OutlinedButton(onClick = { speech.speak(s.text, profile.accent, 0.55f) }) {
                        Icon(Icons.Filled.SlowMotionVideo, null); Text(" Slow")
                    }
                }
                Gap(8.dp)
                Text("💡 ${s.tip}", style = MaterialTheme.typography.bodyMedium)
            }
            Gap()
            Text(
                if (speech.isListening) speech.partial.ifBlank { "Listening..." } else "Pehle suniye, phir mic dabakar sentence bolein",
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MicButton(listening = speech.isListening, level = speech.level) {
                    if (speech.isListening) speech.stopListening()
                    else speech.listen(profile.accent, onResult = { text, secs ->
                        spoken = text
                        val r = compare(s.text, text)
                        result = r
                        val score = r.count { it.ok } * 100 / r.size.coerceAtLeast(1)
                        totalScore += score; attempts++
                        graph.speech.speak(if (score >= 90) "Excellent!" else if (score >= 70) "Good job!" else "Let's try again.", profile.accent, profile.speechRate)
                        graph.store.addActivity(secs, wordCount(text), xp = score / 10)
                    }, onError = { error = null; spoken = it; result = emptyList() })
                }
            }

            result?.let { r ->
                if (r.isEmpty()) {
                    Text(spoken, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else {
                    val score = r.count { it.ok } * 100 / r.size.coerceAtLeast(1)
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ScoreRing(score, size = 64.dp)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(
                                    when {
                                        score >= 90 -> "Excellent! 🎉"
                                        score >= 70 -> "Good, almost there 👍"
                                        else -> "Keep practising 💪"
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                Text("AI ne suna: \"$spoken\"", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Gap(8.dp)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            r.forEach { w ->
                                Text(
                                    w.word,
                                    color = if (w.ok) Good else Bad,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp,
                                    textDecoration = if (w.ok) null else TextDecoration.Underline,
                                    modifier = Modifier.clickable { speech.speak(w.word, profile.accent, 0.6f) },
                                )
                            }
                        }
                        if (r.any { !it.ok }) {
                            Gap(6.dp)
                            Text("Laal words par tap karke unka sahi uchcharan sunein, phir dobara bolein.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Gap()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { spoken = ""; result = null }, modifier = Modifier.weight(1f)) { Text("Try again") }
                Button(
                    onClick = {
                        spoken = ""; result = null
                        if (index < sentences.size - 1) index++ else load()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (index < sentences.size - 1) "Next" else "New set") }
            }
            if (attempts > 0) {
                Gap()
                Text("Average accuracy this round: ${totalScore / attempts}%", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}
