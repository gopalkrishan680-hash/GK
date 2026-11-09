package com.gk.speakup.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gk.speakup.LocalGraph
import com.gk.speakup.ui.Gap
import com.gk.speakup.ui.Pill
import com.gk.speakup.ui.SectionCard
import com.gk.speakup.ui.theme.Bad
import com.gk.speakup.ui.theme.Good

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MistakesScreen(nav: NavController) {
    val graph = LocalGraph.current
    val store = graph.store
    val profile = store.profile
    val byType = store.mistakes.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Mistake journal") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (store.mistakes.isNotEmpty()) TextButton(onClick = { store.clearMistakes() }) { Text("Clear") } },
        )
    }) { padding ->
        if (store.mistakes.isEmpty()) {
            Text(
                "Abhi tak koi galti record nahi hui. AI se baat karein - har correction yahan save hoga taaki aap revise kar sakein.",
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(padding).padding(32.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionCard(container = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Aapki sabse common galtiyan", fontWeight = FontWeight.Bold)
                    Gap(6.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        byType.take(3).forEach { (t, n) -> Pill("${t.replace('_', ' ')}: $n") }
                    }
                    Gap(10.dp)
                    Button(onClick = { nav.navigate("chat/drill") }, modifier = Modifier.fillMaxWidth()) {
                        Text("🎯 Practise these with AI")
                    }
                }
            }
            items(store.mistakes) { c ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(buildAnnotatedString {
                                withStyle(SpanStyle(color = Bad, textDecoration = TextDecoration.LineThrough)) { append(c.original) }
                                append("\n")
                                withStyle(SpanStyle(color = Good, fontWeight = FontWeight.Bold)) { append(c.corrected) }
                            })
                            Gap(4.dp)
                            Text(c.explanation, style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { graph.speech.speak(c.corrected, profile.accent, profile.speechRate) }) {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Listen")
                        }
                    }
                }
            }
        }
    }
}
