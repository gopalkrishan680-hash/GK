package com.gk.channelradar.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.max

@Composable
fun WatchlistScreen(vm: AppViewModel) {
    val list = vm.watchlist.sortedByDescending { w ->
        val first = w.snapshots.first(); val last = w.snapshots.last()
        val days = max(0.5, (last.time - first.time) / 86_400_000.0)
        if (first.subs > 0) (last.subs - first.subs) / days / first.subs else 0.0
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            PageHeader("Watchlist", "Track how your saved channels grow. Refresh once a day (costs ~1 unit).", "⭐") {
                if (vm.watchRefreshing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = androidx.compose.ui.graphics.Color.White)
                else IconButton(onClick = { vm.refreshWatchlist() }, enabled = list.isNotEmpty()) {
                    Icon(Icons.Filled.Refresh, "Refresh", tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
        if (list.isEmpty()) item {
            EmptyState("💫", "Your watchlist is empty", "Tap ♥ on any channel in Results to save it here and track its growth.")
        }
        items(list, key = { it.channel.id }) { w ->
            val c = w.channel
            val first = w.snapshots.first()
            val last = w.snapshots.last()
            val days = (last.time - first.time) / 86_400_000.0
            val dSubs = last.subs - first.subs
            val dViews = last.views - first.views
            val dVideos = last.videos - first.videos
            SoftCard(onClick = { vm.openWatchItem(w) }) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = c.thumb, contentDescription = null,
                            modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${fmtCount(c.subs)} subs · ${fmtCount(c.totalViews)} views · added ${ago(w.addedAt)}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                            )
                        }
                        IconButton(onClick = { vm.toggleWatch(c) }) { Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (w.snapshots.size >= 2) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${sign(dSubs)}${fmtCount(dSubs)} subs · ${sign(dViews)}${fmtCount(dViews)} views · ${sign(dVideos)}$dVideos videos",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (dSubs > 0) GoodGreen else MaterialTheme.colorScheme.onSurface
                                )
                                val perDay = if (days >= 0.04) dSubs / days else 0.0
                                val pct = if (first.subs > 0) dSubs * 100.0 / first.subs else 0.0
                                Text(
                                    "in the last ${"%.1f".format(days)} days · ${fmtCount(perDay)}/day · ${"%+.1f".format(pct)}%",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(Modifier.width(90.dp).height(32.dp)) {
                                Sparkline(w.snapshots.map { it.subs }, Modifier.fillMaxSize())
                            }
                        }
                    } else {
                        Text("Refresh later to see growth.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

private fun sign(x: Long) = if (x > 0) "+" else ""
