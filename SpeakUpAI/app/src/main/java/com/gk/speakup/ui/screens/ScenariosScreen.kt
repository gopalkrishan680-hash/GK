package com.gk.speakup.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.gk.speakup.data.Catalog
import com.gk.speakup.ui.HGap
import com.gk.speakup.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenariosScreen(nav: NavController) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Role-plays") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                Text(
                    "Real-life situations me English bolne ki practice karein. AI character role-play karega aur saath saath aapki galtiyan sudharega.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            items(Catalog.scenarios) { s ->
                SectionCard(Modifier.padding(vertical = 5.dp).clickable { nav.navigate("chat/${s.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.emoji, fontSize = 32.sp)
                        HGap(14.dp)
                        Column {
                            Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(s.description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
