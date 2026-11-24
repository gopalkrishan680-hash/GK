package com.gk.pricehunt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.pricehunt.PriceHuntApp
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.ui.components.ProductRow
import com.gk.pricehunt.ui.components.SectionTitle
import com.gk.pricehunt.ui.components.StoreBadge
import com.gk.pricehunt.util.extractUrl
import kotlinx.coroutines.launch

private val suggestions = listOf(
    "iPhone 15 128GB", "boAt Airdopes 141", "Samsung Galaxy M35", "Nike running shoes men",
    "Philips air fryer", "Puma t-shirt", "Kurti for women", "Redmi Note 13 Pro",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onSearch: (String) -> Unit, onLink: (String) -> Unit, onOpenProduct: (String) -> Unit, onSeeWatchlist: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PriceHuntApp
    val recent by remember { app.repo.dao.observeSearches() }.collectAsState(initial = emptyList())
    val tracked by remember { app.repo.dao.observeTracked() }.collectAsState(initial = emptyList())
    val stores by app.settings.enabledStores.collectAsState()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val assistOn = rememberAssistEnabled()

    fun submit(q: String = text) {
        val s = q.trim()
        if (s.isEmpty()) return
        val url = extractUrl(s)
        if (url != null) onLink(url) else onSearch(s)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("PriceHunt", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Text("Find the lowest price across India's top shopping sites",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Search a product or paste a link") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Searching:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically))
                stores.sortedBy { it.ordinal }.forEach { StoreBadge(it) }
            }
        }

        if (!assistOn) item { AssistPromoCard() }

        if (recent.isNotEmpty()) {
            item {
                SectionTitle("Recent searches") {
                    TextButton(onClick = { scope.launch { app.repo.dao.clearSearches() } }) { Text("Clear") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recent.forEach { r ->
                        InputChip(
                            selected = false,
                            onClick = { text = r.query; submit(r.query) },
                            label = { Text(r.query) },
                            trailingIcon = {
                                Icon(Icons.Filled.Clear, "Remove",
                                    Modifier.size(16.dp).clickable { scope.launch { app.repo.dao.deleteSearch(r.query) } },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                    }
                }
            }
        } else {
            item {
                SectionTitle("Try searching")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        InputChip(selected = false, onClick = { text = s; submit(s) }, label = { Text(s) })
                    }
                }
            }
        }

        if (tracked.isNotEmpty()) {
            item {
                SectionTitle("Your tracked items") { TextButton(onClick = onSeeWatchlist) { Text("See all") } }
            }
            items(tracked.take(3), key = { "t" + it.id }) { p ->
                ProductRow(Store.byId(p.store), p.title, p.imageUrl, p.price, p.mrp, p.rating, p.url) { onOpenProduct(p.id) }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text("Tip: share straight from any shopping app", fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "In Amazon, Flipkart, Myntra or Meesho tap Share on a product and choose PriceHunt. " +
                                "We'll fetch its price, start its price history and compare it across all stores.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }

    }
}
