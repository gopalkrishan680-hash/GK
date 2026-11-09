package com.gk.speakup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gk.speakup.LocalGraph
import com.gk.speakup.data.VocabWord
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.Pill
import com.gk.speakup.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val store = graph.store
    val profile = store.profile
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Vocabulary (${store.vocab.size})") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Flashcards") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("All words") })
            }
            if (store.vocab.isEmpty()) {
                Text(
                    "Abhi koi word save nahi hua. AI se baat karein - naye useful words apne aap yahan save honge.",
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
                return@Column
            }
            if (tab == 0) Flashcards(store.vocab, profile.accent, profile.speechRate)
            else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(store.vocab, key = { it.word }) { w ->
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(w.word, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    if (w.box >= 4) { Text("  "); Pill("Mastered") }
                                }
                                Text(w.meaning, style = MaterialTheme.typography.bodyMedium)
                                Text("\"${w.example}\"", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { graph.speech.speak(w.word + ". " + w.example, profile.accent, profile.speechRate) }) {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Listen")
                            }
                            IconButton(onClick = { store.deleteWord(w) }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Flashcards(all: List<VocabWord>, accent: String, rate: Float) {
    val graph = LocalGraph.current
    // Review lower boxes first (spaced-repetition style).
    val queue = remember(all.size) { all.sortedWith(compareBy({ it.box }, { it.at })).take(20) }
    var i by remember(all.size) { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (i >= queue.size) {
            Gap(40.dp)
            Text("🎉 Review complete!", style = MaterialTheme.typography.headlineSmall)
            Gap()
            Button(onClick = { i = 0; revealed = false }) { Text("Review again") }
            return@Column
        }
        val w = queue[i]
        Text("${i + 1} / ${queue.size}", style = MaterialTheme.typography.labelLarge)
        Gap()
        SectionCard(
            Modifier.heightIn(min = 240.dp).clickable { revealed = true },
            container = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(w.word, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = { graph.speech.speak(w.word, accent, rate) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Listen") }
            Gap()
            if (revealed) {
                Text(w.meaning, style = MaterialTheme.typography.bodyLarge)
                Gap(8.dp)
                Text("\"${w.example}\"", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Tap to see meaning. Pehle khud yaad karke ek sentence bolne ki koshish karein!", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Gap()
        if (revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    graph.store.updateWord(w.copy(box = 0)); revealed = false; i++
                }, modifier = Modifier.weight(1f)) { Text("Again") }
                Button(onClick = {
                    graph.store.updateWord(w.copy(box = (w.box + 1).coerceAtMost(5))); revealed = false; i++
                }, modifier = Modifier.weight(1f)) { Text("I know it") }
            }
        }
    }
}
