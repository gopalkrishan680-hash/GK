package com.gk.pricehunt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.pricehunt.PriceHuntApp
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.data.db.ProductEntity
import com.gk.pricehunt.ui.components.ProductRow
import com.gk.pricehunt.ui.components.SectionTitle
import com.gk.pricehunt.ui.components.StoreBadge
import com.gk.pricehunt.ui.theme.DealGreen
import com.gk.pricehunt.util.rupees
import com.gk.pricehunt.util.timeAgo
import com.gk.pricehunt.work.PriceCheckWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Watchlist

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(onOpenProduct: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PriceHuntApp
    val tracked by remember { app.repo.dao.observeTracked() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Price tracker") },
                actions = {
                    if (refreshing) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else IconButton(enabled = tracked.isNotEmpty(), onClick = {
                        refreshing = true
                        scope.launch {
                            tracked.map { p -> async { app.repo.fetchProductPage(p.url, knownId = p.id) } }.awaitAll()
                            refreshing = false
                        }
                    }) { Icon(Icons.Filled.Refresh, "Check all prices now") }
                },
            )
        }
    ) { pad ->
        if (tracked.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No tracked products yet", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open any product and tap \"Track price\". PriceHunt checks it in the background, " +
                        "builds its price history and notifies you when the price drops.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val reached = tracked.count { it.targetPrice != null && it.price <= it.targetPrice }
            if (reached > 0) item {
                Text("🎉 $reached item${if (reached > 1) "s have" else " has"} reached your target price!",
                    color = DealGreen, fontWeight = FontWeight.Bold)
            }
            items(tracked, key = { it.id }) { p -> TrackedItem(p, onOpenProduct) }
        }
    }
}

@Composable
private fun TrackedItem(p: ProductEntity, onOpenProduct: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PriceHuntApp
    val scope = rememberCoroutineScope()
    var lowest by remember(p.id, p.price) { mutableStateOf<Double?>(null) }
    LaunchedEffect(p.id, p.price) { lowest = app.repo.dao.lowest(p.id) }
    Column {
        ProductRow(Store.byId(p.store), p.title, p.imageUrl, p.price, p.mrp, p.rating, p.url,
            highlight = if (p.targetPrice != null && p.price <= p.targetPrice) "Target hit" else null) { onOpenProduct(p.id) }
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    lowest?.let { append("Lowest ${rupees(it)}") }
                    p.targetPrice?.let { append(" · Target ${rupees(it)}") }
                    append(" · ${timeAgo(p.lastChecked)}")
                },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { app.repo.setTracked(p.id, false, null) } }) {
                Icon(Icons.Filled.Delete, "Stop tracking", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PriceHuntApp
    val settings = app.settings
    val enabled by settings.enabledStores.collectAsState()
    var interval by remember { mutableIntStateOf(settings.checkIntervalHours) }
    var anyDrop by remember { mutableStateOf(settings.alertOnAnyDrop) }
    var hideSponsored by remember { mutableStateOf(settings.hideSponsored) }
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Stores to search")
            Text("Fewer stores = faster results.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Store.entries.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    StoreBadge(s)
                    Spacer(Modifier.width(10.dp))
                    Text(s.displayName, modifier = Modifier.weight(1f))
                    Switch(
                        checked = s in enabled,
                        onCheckedChange = { on -> if (on || enabled.size > 1) settings.setStoreEnabled(s, on) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Price drop alerts")
            Text("Check tracked prices in background", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                listOf(0 to "Off", 3 to "3 h", 6 to "6 h", 12 to "12 h", 24 to "Daily").forEach { (h, label) ->
                    FilterChip(selected = interval == h, onClick = {
                        interval = h
                        settings.checkIntervalHours = h
                        PriceCheckWorker.schedule(ctx, h)
                    }, label = { Text(label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Alert on every price drop", fontSize = 14.sp)
                    Text("Off = only alert when your target price is reached", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = anyDrop, onCheckedChange = { anyDrop = it; settings.alertOnAnyDrop = it })
            }
            OutlinedButton(onClick = { PriceCheckWorker.runNow(ctx) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Run a price check now")
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Search results")
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Hide sponsored listings", fontSize = 14.sp, modifier = Modifier.weight(1f))
                Switch(checked = hideSponsored, onCheckedChange = { hideSponsored = it; settings.hideSponsored = it })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Data")
            TextButton(onClick = { confirmClear = true }) { Text("Clear search history & untracked price data") }

            SectionTitle("About")
            Text(
                "PriceHunt reads the public search pages of each store directly on your phone, the same way your browser does. " +
                    "No account or server is involved and your data stays on your device.\n\n" +
                    "Price history is recorded from the first time PriceHunt sees a product, so it grows the more you search and track. " +
                    "Prices, offers and stock can change quickly and bank/coupon offers are not included — always confirm the final price in the store app before buying.\n\n" +
                    "PriceHunt is not affiliated with Amazon, Flipkart, Myntra, Meesho or any other store.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Version 1.0.0", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear data?") },
            text = { Text("Recent searches and price history of products you're not tracking will be deleted. Tracked items are kept.") },
            confirmButton = { Button(onClick = { confirmClear = false; scope.launch { app.repo.clearHistoryData() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ Shared link

/** Handles a product link (shared from a shopping app or pasted in search). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkScreen(url: String, onBack: () -> Unit, onResolved: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PriceHuntApp
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(url, attempt) {
        error = null
        val r = app.repo.fetchProductPage(url)
        if (r.product != null) onResolved(r.product.id) else error = r.error ?: "Couldn't read this product"
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Shared product") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (error == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Reading product details…", fontWeight = FontWeight.SemiBold)
                Text(url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            } else {
                Text(error!!, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Supported: " + Store.entries.joinToString { it.displayName }, fontSize = 12.sp,
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { attempt++ }) { Text("Try again") }
            }
        }
    }
}
