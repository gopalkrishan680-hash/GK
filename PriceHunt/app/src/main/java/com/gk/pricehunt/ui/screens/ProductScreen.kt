package com.gk.pricehunt.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.gk.pricehunt.PriceHuntApp
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.data.db.PricePoint
import com.gk.pricehunt.scrape.Matcher
import com.gk.pricehunt.ui.components.Pill
import com.gk.pricehunt.ui.components.PriceChart
import com.gk.pricehunt.ui.components.PriceLine
import com.gk.pricehunt.ui.components.ProductImage
import com.gk.pricehunt.ui.components.RatingText
import com.gk.pricehunt.ui.components.SectionTitle
import com.gk.pricehunt.ui.components.StoreBadge
import com.gk.pricehunt.ui.theme.DealGreen
import com.gk.pricehunt.ui.theme.DropRed
import com.gk.pricehunt.util.isAppInstalled
import com.gk.pricehunt.util.openInStore
import com.gk.pricehunt.util.rupees
import com.gk.pricehunt.util.shareText
import com.gk.pricehunt.util.timeAgo
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(id: String, onBack: () -> Unit, onCompare: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PriceHuntApp
    val repo = app.repo
    val product by remember(id) { repo.dao.observe(id) }.collectAsState(initial = null)
    val history by remember(id) { repo.dao.observeHistory(id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showTrackDialog by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Product details", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    product?.let { p ->
                        IconButton(onClick = {
                            shareText(ctx, "${p.title}\n${rupees(p.price)} on ${Store.byId(p.store)?.displayName}\n${p.url}")
                        }) { Icon(Icons.Filled.Share, "Share") }
                    }
                },
            )
        }
    ) { pad ->
        val p = product
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val store = Store.byId(p.store)
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ProductImage(p.imageUrl, 200.dp) }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoreBadge(store, p.store)
                RatingText(p.rating)
                if (!p.inStock) Pill("Out of stock", DropRed.copy(alpha = 0.12f), DropRed)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            PriceLine(p.price, p.mrp, big = true)
            Text(
                "Checked ${timeAgo(p.lastChecked)}" + (p.lastError?.let { " · $it" } ?: ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            val installed = isAppInstalled(ctx, store?.appPackage)
            Button(
                onClick = { openInStore(ctx, p.url, store) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(store?.color ?: 0xFF4F46E5)),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(
                    if (installed) "Open in ${store?.displayName} app" else "Open on ${store?.displayName ?: "store"}",
                    fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCompare(Matcher.queryFromTitle(p.title)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Compare stores")
                }
                OutlinedButton(
                    onClick = {
                        if (p.tracked) scope.launch { repo.setTracked(p.id, false, null) }
                        else showTrackDialog = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(if (p.tracked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null, Modifier.size(18.dp),
                        tint = if (p.tracked) DropRed else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(if (p.tracked) "Tracking" else "Track price")
                }
            }
            if (p.tracked) {
                Text(
                    "You'll get a notification when the price drops" + (p.targetPrice?.let { " (target ${rupees(it)})" } ?: "") + ".",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }

            // ---------------- price history ----------------
            SectionTitle("Price history") {
                TextButton(enabled = !refreshing, onClick = {
                    refreshing = true
                    scope.launch {
                        message = repo.fetchProductPage(p.url, knownId = p.id).error
                        refreshing = false
                    }
                }) {
                    if (refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("Check now")
                }
            }
            message?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
            HistoryCard(history, p.price)

            if (store == Store.AMAZON) {
                TextButton(onClick = {
                    openInStore(ctx, "https://keepa.com/#!product/10-${p.id.substringAfter(':')}", null)
                }) { Text("See full long-term Amazon history on Keepa →") }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showTrackDialog) {
            TrackDialog(
                current = p.price,
                onDismiss = { showTrackDialog = false },
                onConfirm = { target ->
                    showTrackDialog = false
                    scope.launch { repo.setTracked(p.id, true, target) }
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
            )
        }
    }
}

@Composable
private fun HistoryCard(history: List<PricePoint>, current: Double) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            val prices = history.map { it.price } + current
            val low = prices.min()
            val high = prices.max()
            val avg = prices.average()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Lowest", rupees(low), DealGreen)
                Stat("Average", rupees(avg), MaterialTheme.colorScheme.onSurface)
                Stat("Highest", rupees(high), DropRed)
            }
            Spacer(Modifier.height(10.dp))
            val (verdict, color) = when {
                history.size < 2 -> "Tracking started — history builds up every time the price is checked." to MaterialTheme.colorScheme.onSurfaceVariant
                current <= low * 1.01 -> "Great time to buy: this is the lowest price we've seen." to DealGreen
                current < avg -> "Good price: below its usual average." to DealGreen
                current > avg * 1.05 -> "Price is higher than usual. Consider waiting or set a target price." to DropRed
                else -> "Fair price: close to its usual average." to MaterialTheme.colorScheme.onSurface
            }
            Text(verdict, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
            Spacer(Modifier.height(10.dp))
            PriceChart(history, current)
            Text(
                "${history.size} price record${if (history.size == 1) "" else "s"} · since ${
                    history.firstOrNull()?.let { timeAgo(it.time) } ?: "now"
                }",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun TrackDialog(current: Double, onDismiss: () -> Unit, onConfirm: (Double?) -> Unit) {
    var text by remember { mutableStateOf(((current * 0.9).toInt()).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track this price") },
        text = {
            Column {
                Text("Current price: ${rupees(current)}. Set a target price to get an alert when it drops to that level (leave empty to be alerted on any drop).",
                    fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { v -> text = v.filter { it.isDigit() } },
                    label = { Text("Target price (₹)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text.toDoubleOrNull()) }) { Text("Start tracking") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
