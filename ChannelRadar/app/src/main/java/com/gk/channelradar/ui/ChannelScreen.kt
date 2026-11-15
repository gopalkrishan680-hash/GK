package com.gk.channelradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gk.channelradar.data.ChannelResult
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelScreen(vm: AppViewModel, r: ChannelResult) {
    val ctx = LocalContext.current
    val c = r.channel
    val watched = vm.isWatched(c.id)
    val now = System.currentTimeMillis()
    val ageDays = if (c.createdAt > 0) (now - c.createdAt) / 86_400_000L else 0
    val hitIds = r.hits.map { it.video.id }.toSet()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.closeDetail() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { shareText(ctx, "${c.title} (${fmtCount(c.subs)} subs)\n${c.url}") }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { vm.hideChannel(c.id) }) { Icon(Icons.Filled.Clear, "Hide channel") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = c.thumb, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(c.country.takeIf { it.isNotBlank() }, if (c.createdAt > 0) "since ${fmtDate(c.createdAt)}" else null).joinToString(" · "),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (r.score > 0) ScoreBadge(r.score)
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Stat("Subscribers", fmtCount(c.subs), Modifier.weight(1f))
                        Stat("Total views", fmtCount(c.totalViews), Modifier.weight(1f))
                        Stat("Videos", fmtCount(c.videoCount), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        Stat("Channel age", if (ageDays > 0) (if (ageDays < 60) "$ageDays days" else "${ageDays / 30} months") else "-", Modifier.weight(1f))
                        Stat("Normal views", r.baselineMedian?.let { fmtCount(it) } ?: "-", Modifier.weight(1f), WarnAmber)
                        Stat(
                            "Videos > subs", r.consistency?.let { "${(it * 100).toInt()}%" } ?: "-", Modifier.weight(1f),
                            if ((r.consistency ?: 0.0) >= 0.5) GoodGreen else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (c.videoCount > 0 && ageDays > 0) {
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Stat("Views / video", fmtCount(c.totalViews / max(c.videoCount, 1)), Modifier.weight(1f))
                            Stat("Uploads / week", "%.1f".format(c.videoCount * 7.0 / max(ageDays, 1)), Modifier.weight(1f))
                            Stat("Subs / day (avg)", fmtCount(c.subs / max(ageDays, 1)), Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (r.tags.isNotEmpty()) item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { r.tags.forEach { Tag(it) } }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(ctx, c.url) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("YouTube")
                }
                OutlinedButton(onClick = { vm.toggleWatch(c) }, modifier = Modifier.weight(1f)) {
                    Icon(if (watched) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null)
                    Spacer(Modifier.width(4.dp)); Text(if (watched) "Watching" else "Watchlist")
                }
            }
        }

        if (c.description.isNotBlank()) item {
            Text(c.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }

        if (r.hits.isNotEmpty()) {
            item { SectionTitle("🔥 Breakout videos (${r.hits.size})") }
            items(r.hits, key = { "hit_" + it.video.id }) { sv ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(10.dp)) {
                        VideoRow(sv) { openUrl(ctx, sv.video.url) }
                        Text(
                            "Found via: ${sv.video.foundBy} · 👍 ${fmtCount(sv.video.likes)} · 💬 ${fmtCount(sv.video.comments)} · engagement ${"%.1f".format(sv.engagement * 100)}%",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("📊 Recent uploads", Modifier.weight(1f))
                if (vm.detailLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { vm.loadUploads() }) { Icon(Icons.Filled.Refresh, "Reload uploads") }
            }
        }
        if (r.recentUploads.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        ViewsBarChart(r.recentUploads, r.baselineMedian, c.subs, hitIds)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Legend("Breakout", MaterialTheme.colorScheme.primary)
                            Legend("Normal views", WarnAmber)
                            Legend("Subscribers", GoodGreen)
                        }
                    }
                }
            }
            items(r.recentUploads, key = { "up_" + it.id }) { v ->
                val ratio = v.views.toDouble() / max(c.subs, 50L)
                val out = r.baselineMedian?.let { v.views.toDouble() / max(it, 10L) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(end = 10.dp)) { Thumb(v, 104) }
                    Column(Modifier.weight(1f)) {
                        Text(v.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("👁 ${fmtCount(v.views)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("${fmtX(ratio)} subs", fontSize = 12.sp, color = if (ratio >= 1) GoodGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                            out?.let { Text("${fmtX(it)} avg", fontSize = 12.sp, color = if (it >= 2) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(ago(v.publishedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { openUrl(ctx, v.url) }) { Icon(Icons.Filled.PlayArrow, "Open") }
                }
            }
        } else if (!vm.detailLoading) item {
            Text("Uploads not loaded yet. Tap refresh (~2 quota units).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Legend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
