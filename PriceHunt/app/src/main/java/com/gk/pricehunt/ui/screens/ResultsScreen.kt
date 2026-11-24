package com.gk.pricehunt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gk.pricehunt.data.ProductResult
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.data.StoreStatus
import com.gk.pricehunt.ui.components.ProductImage
import com.gk.pricehunt.ui.components.ProductRow
import com.gk.pricehunt.ui.components.SectionTitle
import com.gk.pricehunt.ui.components.StoreBadge
import com.gk.pricehunt.ui.theme.DealGreen
import com.gk.pricehunt.util.openInStore
import com.gk.pricehunt.util.rupees

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(query: String, onBack: () -> Unit, onOpenProduct: (String) -> Unit) {
    val vm: SearchViewModel = viewModel(key = "search:$query")
    LaunchedEffect(query) { vm.searchIfNew(query) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    var sort by rememberSaveable { mutableStateOf(SortMode.RELEVANCE) }
    var storeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var closeOnly by rememberSaveable { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }

    val best = state.bestPerStore
    val list = remember(state.results, sort, storeFilter, closeOnly) {
        state.results
            .filter { storeFilter == null || it.store.id == storeFilter }
            .filter { !closeOnly || it.relevance >= 0.5 }
            .let { l ->
                when (sort) {
                    SortMode.RELEVANCE -> l.sortedWith(compareByDescending<ProductResult> { (it.relevance * 10).toInt() }.thenBy { it.price })
                    SortMode.PRICE_LOW -> l.sortedBy { it.price }
                    SortMode.PRICE_HIGH -> l.sortedByDescending { it.price }
                    SortMode.DISCOUNT -> l.sortedByDescending { it.discountPercent ?: 0 }
                    SortMode.RATING -> l.sortedByDescending { it.rating ?: 0.0 }
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(query, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                        Text(
                            if (state.loading) "Checking ${state.doneCount}/${state.statuses.size} stores…"
                            else "${state.results.size} results from ${state.statuses.size} stores",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { vm.search(query, fresh = true) }) { Icon(Icons.Filled.Refresh, "Search again") } },
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) item {
                LinearProgressIndicator(
                    progress = { (state.doneCount.toFloat() / state.statuses.size.coerceAtLeast(1)).coerceAtLeast(0.05f) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }

            // ---- store status strip ----
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.statuses.toSortedMap(compareBy { it.ordinal }).forEach { (store, st) ->
                        StoreStatusChip(store, st,
                            onRetry = { vm.search(query, onlyStore = store) },
                            onOpenWeb = { openInStore(ctx, store.searchUrl(query), store) })
                    }
                }
            }

            // ---- best deal hero ----
            if (best.isNotEmpty()) {
                item { BestDealCard(best, onOpenProduct) }
                if (best.size > 1) {
                    item { SectionTitle("Same item, every store") }
                    item { ComparisonTable(best, onOpenProduct) }
                }
            } else if (!state.loading && state.results.isEmpty()) {
                item { EmptyState(state) }
            }

            // ---- all results ----
            if (state.results.isNotEmpty()) {
                item {
                    SectionTitle("All results (${list.size})") {
                        Box {
                            TextButton(onClick = { sortMenu = true }) {
                                Text(sort.label, fontSize = 13.sp)
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                SortMode.entries.forEach {
                                    DropdownMenuItem(text = { Text(it.label) }, onClick = { sort = it; sortMenu = false })
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = storeFilter == null, onClick = { storeFilter = null }, label = { Text("All") })
                        state.results.groupBy { it.store }.toSortedMap(compareBy { it.ordinal }).forEach { (s, l) ->
                            FilterChip(
                                selected = storeFilter == s.id,
                                onClick = { storeFilter = if (storeFilter == s.id) null else s.id },
                                label = { Text("${s.displayName} (${l.size})") },
                            )
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Hide accessories & unrelated items", fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Switch(checked = closeOnly, onCheckedChange = { closeOnly = it })
                    }
                }
                items(list, key = { it.id }) { r ->
                    ProductRow(r.store, r.title, r.imageUrl, r.price, r.mrp, r.rating, r.url,
                        highlight = if (r.sponsored) null else if (best.firstOrNull()?.id == r.id) "Lowest" else null,
                        onClick = { onOpenProduct(r.id) })
                }
            }
        }
    }
}

@Composable
private fun StoreStatusChip(store: Store, st: StoreStatus, onRetry: () -> Unit, onOpenWeb: () -> Unit) {
    val (text, clickable) = when (st) {
        StoreStatus.Waiting -> "waiting" to null
        StoreStatus.Loading -> "" to null
        is StoreStatus.Done -> (if (st.count == 0) "no results · open" else "${st.count}") to (if (st.count == 0) onOpenWeb else null)
        StoreStatus.Blocked -> "blocked · open" to onOpenWeb
        is StoreStatus.Failed -> "failed · retry" to onRetry
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface)
            .let { m -> if (clickable != null) m.clickable(onClick = clickable) else m }
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreBadge(store)
        Spacer(Modifier.width(6.dp))
        if (st is StoreStatus.Loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        else {
            if (st is StoreStatus.Blocked || st is StoreStatus.Failed) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BestDealCard(best: List<ProductResult>, onOpenProduct: (String) -> Unit) {
    val ctx = LocalContext.current
    val cheapest = best.first()
    val costliest = best.last()
    Card(
        onClick = { onOpenProduct(cheapest.id) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("LOWEST PRICE FOUND", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DealGreen, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProductImage(cheapest.imageUrl, 84.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(cheapest.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rupees(cheapest.price), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(8.dp))
                        StoreBadge(cheapest.store)
                    }
                    if (best.size > 1 && costliest.price > cheapest.price) {
                        Text(
                            "Save ${rupees(costliest.price - cheapest.price)} vs ${costliest.store.displayName}",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DealGreen,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { openInStore(ctx, cheapest.url, cheapest.store) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(cheapest.store.color)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Buy on ${cheapest.store.displayName}", fontWeight = FontWeight.Bold, color = Color.White) }
        }
    }
}

@Composable
private fun ComparisonTable(best: List<ProductResult>, onOpenProduct: (String) -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val min = best.first().price
        best.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Card(
                onClick = { onOpenProduct(r.id) },
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(0.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(78.dp)) { StoreBadge(r.store) }
                    Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(rupees(r.price), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            color = if (i == 0) DealGreen else MaterialTheme.colorScheme.onSurface)
                        if (i > 0) Text("+${rupees(r.price - min)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        else Text("cheapest", fontSize = 11.sp, color = DealGreen)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(state: SearchState) {
    val blocked = state.statuses.values.count { it is StoreStatus.Blocked || it is StoreStatus.Failed }
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No matching products found", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            if (blocked > 0) "Some stores didn't respond. Tap a store above to open its search directly, or try again."
            else "Try a shorter or more general search, e.g. brand + model.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
