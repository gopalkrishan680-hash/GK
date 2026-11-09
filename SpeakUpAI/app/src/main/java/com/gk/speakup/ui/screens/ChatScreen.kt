package com.gk.speakup.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.speakup.LocalGraph
import com.gk.speakup.ai.ChatTurn
import com.gk.speakup.ai.Prompts
import com.gk.speakup.data.Catalog
import com.gk.speakup.data.Correction
import com.gk.speakup.data.Scenario
import com.gk.speakup.data.SessionRecord
import com.gk.speakup.data.VocabWord
import com.gk.speakup.data.strings
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.HGap
import com.gk.speakup.ui.MicButton
import com.gk.speakup.ui.Pill
import com.gk.speakup.ui.ScoreRing
import com.gk.speakup.ui.theme.Bad
import com.gk.speakup.ui.theme.Good
import com.gk.speakup.ui.theme.scoreColor
import com.gk.speakup.ui.wordCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class Feedback(
    val corrections: List<Correction>,
    val better: String,
    val score: Int,
    val praise: String,
    val newWords: List<VocabWord>,
)

private data class UiMsg(val id: Long, val fromUser: Boolean, val text: String, val feedback: Feedback? = null)

private data class Report(val summary: String, val mistakes: List<String>, val tips: List<String>, val score: Int, val level: String)

fun resolveScenario(id: String, graph: com.gk.speakup.AppGraph): Pair<Scenario, String> {
    val name = graph.store.profile.name
    return when {
        id == "drill" -> {
            val recent = graph.store.mistakes.take(15)
            val list = if (recent.isEmpty()) "(no saved mistakes yet - focus on common Hindi-speaker mistakes: articles, tenses, prepositions)"
            else recent.joinToString("\n") { "- said \"${it.original}\" instead of \"${it.corrected}\"" }
            Catalog.mistakeDrill to "LEARNER'S PAST MISTAKES:\n$list"
        }
        id.startsWith("topic:") -> {
            val topic = id.removePrefix("topic:")
            Scenario(
                "topic", "Daily Topic", "🗓️", topic,
                "Have a conversation about this topic: $topic. Ask about the learner's own experiences and opinions.",
                "Hi $name! Today's topic is $topic. Let's talk about it. What comes to your mind first?",
            ) to ""
        }
        id.startsWith("word:") -> {
            val word = id.removePrefix("word:")
            Scenario(
                "word", "Word Practice", "🔤", word,
                "Help the learner actively use the word/phrase \"$word\". Ask questions that make them use it in their own sentences, check they use it correctly, then also practise related words.",
                "Let's practise today's word: $word. Can you make your own sentence with it? Tell me something about your life.",
            ) to ""
        }
        else -> {
            val s = Catalog.scenarios.firstOrNull { it.id == id } ?: Catalog.freeTalk
            if (s.id == "free") s.copy(opener = "Hi $name! It's so nice to talk to you. How has your day been so far?") to ""
            else s to ""
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(scenarioId: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val store = graph.store
    val speech = graph.speech
    val profile = store.profile
    val persona = Catalog.persona(profile.personaId)
    val scope = rememberCoroutineScope()

    val (scenario, extra) = remember(scenarioId) { resolveScenario(scenarioId, graph) }
    val system = remember(scenarioId) { Prompts.tutorSystem(profile, persona, scenario, extra) }

    val history = remember { mutableListOf<ChatTurn>() }
    val messages = remember { mutableStateListOf<UiMsg>() }
    var thinking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryText by remember { mutableStateOf<String?>(null) }
    var handsFree by remember { mutableStateOf(profile.handsFree) }
    var active by remember { mutableStateOf(true) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var showExit by remember { mutableStateOf(false) }
    var reportLoading by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<Report?>(null) }
    val startedAt = remember { System.currentTimeMillis() }
    val listState = rememberLazyListState()

    fun userTurns() = messages.count { it.fromUser }

    lateinit var startListening: () -> Unit

    fun speakReply(text: String) {
        speech.speak(text, profile.accent, profile.speechRate) {
            if (handsFree && active && !thinking && report == null && !showExit) startListening()
        }
    }

    fun send(text: String, seconds: Long) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        error = null
        retryText = null
        val msgId = System.nanoTime()
        if (messages.none { it.id == msgId }) messages.add(UiMsg(msgId, true, clean))
        history.add(ChatTurn(true, clean))
        thinking = true
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    graph.ai().askJson(system, history.toList(), Prompts.tutorSchema)
                }
                history.add(ChatTurn(false, json.toString()))
                val fb = parseFeedback(json)
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) messages[idx] = messages[idx].copy(feedback = fb)
                val reply = json.optString("reply")
                messages.add(UiMsg(System.nanoTime(), false, reply))
                store.addMistakes(fb.corrections)
                store.addWords(fb.newWords)
                val words = wordCount(clean)
                store.addActivity(seconds, words, xp = words + fb.score / 10)
                thinking = false
                speakReply(reply)
            } catch (e: Exception) {
                history.removeAt(history.lastIndex)
                messages.removeAll { it.id == msgId }
                thinking = false
                error = e.message ?: "Kuch gadbad ho gayi"
                retryText = clean
            }
        }
    }

    startListening = {
        error = null
        speech.listen(profile.accent, onResult = { text, secs -> send(text, secs) }, onError = { error = it })
    }

    fun endSession() {
        active = false
        speech.stopSpeaking()
        speech.cancelListening()
        if (userTurns() < 2) {
            onBack(); return
        }
        reportLoading = true
        val minutes = ((System.currentTimeMillis() - startedAt) / 60000).toInt().coerceAtLeast(1)
        val transcript = buildString {
            append("Activity: ${scenario.title}\n\n")
            messages.forEach { m -> append(if (m.fromUser) "LEARNER: " else "COACH: ").append(m.text).append("\n") }
        }
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    graph.ai().askJson(
                        Prompts.summarySystem(profile), listOf(ChatTurn(true, transcript)), Prompts.summarySchema,
                    )
                }
                val r = Report(
                    json.optString("summary"), json.optJSONArray("top_mistakes").strings(),
                    json.optJSONArray("tips").strings(), json.optInt("score"), json.optString("estimated_level"),
                )
                store.addSession(SessionRecord(scenario.title, r.summary, r.score, minutes))
                store.addActivity(0, 0, xp = 20, sessionDone = true)
                report = r
            } catch (e: Exception) {
                store.addSession(SessionRecord(scenario.title, "Practice session", 0, minutes))
                store.addActivity(0, 0, xp = 20, sessionDone = true)
                report = Report("Great job practising today! (Report could not be generated: ${e.message})", emptyList(), emptyList(), 0, "")
            } finally {
                reportLoading = false
            }
        }
    }

    // Start: the coach opens the conversation.
    LaunchedEffect(scenarioId) {
        if (history.isEmpty()) {
            history.add(ChatTurn(true, "(The learner opened the practice session and is ready. Begin the activity.)"))
            val openerJson = JSONObject()
                .put("reply", scenario.opener).put("corrections", JSONArray()).put("better_version", "")
                .put("score", 0).put("praise", "").put("new_words", JSONArray())
            history.add(ChatTurn(false, openerJson.toString()))
            messages.add(UiMsg(System.nanoTime(), false, scenario.opener))
            speakReply(scenario.opener)
        }
    }

    LaunchedEffect(messages.size, thinking, speech.partial) {
        val count = messages.size + (if (thinking || speech.isListening) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    DisposableEffect(Unit) {
        onDispose {
            speech.stopSpeaking()
            speech.cancelListening()
        }
    }

    BackHandler {
        if (userTurns() >= 2 && report == null) showExit = true else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (userTurns() >= 2 && report == null) showExit = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = {
                    Column {
                        Text("${persona.emoji} ${persona.name}", fontWeight = FontWeight.Bold)
                        Text("${scenario.emoji} ${scenario.title} · Level ${profile.level}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    TextButton(onClick = { endSession() }) { Text("End") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    if (m.fromUser) UserBubble(m) else CoachBubble(
                        m.text,
                        onReplay = { speech.speak(m.text, profile.accent, profile.speechRate) },
                        onSlow = { speech.speak(m.text, profile.accent, profile.speechRate * 0.65f) },
                    )
                }
                if (speech.isListening) {
                    item(key = "partial") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    speech.partial.ifBlank { "Listening..." },
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                } else if (thinking) {
                    item(key = "thinking") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            HGap()
                            Text("${persona.name} is listening & checking your English...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            error?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        retryText?.let { t -> TextButton(onClick = { send(t, 0) }) { Text("Retry") } }
                    }
                }
            }

            // ---- controls
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (typing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = typed, onValueChange = { typed = it },
                                placeholder = { Text("Type in English...") },
                                modifier = Modifier.weight(1f), maxLines = 4,
                            )
                            IconButton(
                                onClick = { val t = typed; typed = ""; send(t, (wordCount(t) / 2L).coerceAtLeast(1)) },
                                enabled = typed.isNotBlank() && !thinking,
                            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = { typing = !typing }) { Icon(Icons.Filled.Keyboard, "Type") }
                            Text("Type", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { send("I'm not sure what to say. Can you give me some ideas or a sentence starter?", 0) },
                                enabled = !thinking && !speech.isListening,
                            ) { Icon(Icons.Filled.Lightbulb, "Hint") }
                            Text("Hint", style = MaterialTheme.typography.labelSmall)
                        }
                        MicButton(
                            listening = speech.isListening,
                            level = speech.level,
                            enabled = !thinking,
                        ) {
                            if (speech.isListening) speech.stopListening()
                            else { active = true; startListening() }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilterChip(
                                selected = handsFree,
                                onClick = { handsFree = !handsFree },
                                label = { Text("Auto") },
                            )
                            Text("hands-free", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(
                        when {
                            speech.isListening -> "Bolte rahiye... ruk jaane par apne aap send hoga"
                            speech.isSpeaking -> "${persona.name} bol rahi/rahe hain..."
                            thinking -> "Thinking..."
                            else -> "Mic dabayein aur English me bolein"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("End this session?") },
            text = { Text("Session khatam karke apni report dekhein?") },
            confirmButton = { Button(onClick = { showExit = false; endSession() }) { Text("End & see report") } },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("Keep talking") } },
        )
    }

    if (reportLoading || report != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (reportLoading) "Preparing your report..." else "Session report") },
            text = {
                if (reportLoading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else report?.let { r ->
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ScoreRing(r.score, size = 64.dp)
                            HGap(12.dp)
                            if (r.level.isNotBlank()) Pill("Spoke at ${r.level} level")
                        }
                        Gap(8.dp)
                        Text(r.summary)
                        if (r.mistakes.isNotEmpty()) {
                            Gap(8.dp)
                            Text("Top mistakes", fontWeight = FontWeight.Bold)
                            r.mistakes.forEach { Text("• $it") }
                        }
                        if (r.tips.isNotEmpty()) {
                            Gap(8.dp)
                            Text("Tips for next time", fontWeight = FontWeight.Bold)
                            r.tips.forEach { Text("• $it") }
                        }
                    }
                }
            },
            confirmButton = {
                if (!reportLoading) Button(onClick = { report = null; onBack() }) { Text("Done") }
            },
        )
    }
}

private fun parseFeedback(json: JSONObject): Feedback {
    val corrections = mutableListOf<Correction>()
    json.optJSONArray("corrections")?.let { a ->
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            corrections.add(Correction(c.optString("original"), c.optString("corrected"), c.optString("explanation"), c.optString("type")))
        }
    }
    val words = mutableListOf<VocabWord>()
    json.optJSONArray("new_words")?.let { a ->
        for (i in 0 until a.length()) {
            val w = a.getJSONObject(i)
            words.add(VocabWord(w.optString("word"), w.optString("meaning"), w.optString("example")))
        }
    }
    return Feedback(corrections, json.optString("better_version"), json.optInt("score"), json.optString("praise"), words)
}

@Composable
private fun CoachBubble(text: String, onReplay: () -> Unit, onSlow: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                Row {
                    IconButton(onClick = onReplay, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, "Replay", Modifier.size(18.dp))
                    }
                    IconButton(onClick = onSlow, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.SlowMotionVideo, "Slow", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(m: UiMsg) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                m.text, color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(12.dp),
            )
        }
        m.feedback?.let { FeedbackCard(it) }
    }
}

@Composable
private fun FeedbackCard(fb: Feedback) {
    Gap(4.dp)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.widthIn(max = 320.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(scoreColor(fb.score)).padding(horizontal = 8.dp, vertical = 2.dp)
                ) { Text("${fb.score}", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold) }
                HGap()
                Text(
                    if (fb.corrections.isEmpty()) "✅ ${fb.praise.ifBlank { "Perfect!" }}" else fb.praise,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
            }
            fb.corrections.forEach { c ->
                Gap(6.dp)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Bad, textDecoration = TextDecoration.LineThrough)) { append(c.original) }
                        append("  →  ")
                        withStyle(SpanStyle(color = Good, fontWeight = FontWeight.Bold)) { append(c.corrected) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${c.type.replace('_', ' ').replaceFirstChar { it.uppercase() }}: ${c.explanation}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (fb.better.isNotBlank()) {
                Gap(6.dp)
                Text("💡 Better: ${fb.better}", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
            if (fb.newWords.isNotEmpty()) {
                Gap(6.dp)
                Text(
                    "📘 Saved: " + fb.newWords.joinToString { it.word },
                    style = MaterialTheme.typography.labelSmall, fontSize = 12.sp,
                )
            }
        }
    }
}
