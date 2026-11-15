package com.gk.viralradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gk.viralradar.data.Fmt
import com.gk.viralradar.data.Scored

@Composable
fun ScoreBadge(score: Int, modifier: Modifier = Modifier, size: Int = 46) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(scoreColor(score)),
        contentAlignment = Alignment.Center,
    ) {
        Text("$score", color = Color.White, fontWeight = FontWeight.Black, fontSize = (size / 2.6).sp)
    }
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant, textColor: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun VideoCard(item: Scored, rank: Int?, tracked: Boolean, onClick: () -> Unit, onTrack: () -> Unit) {
    val v = item.video
    val s = item.score
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Box {
                AsyncImage(
                    model = v.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(128.dp).height(76.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Text(
                    when {
                        v.liveNow -> "🔴 LIVE"
                        v.wasLive -> "STREAM ${Fmt.duration(v.durationSec)}"
                        v.isShort -> "SHORT"
                        else -> Fmt.duration(v.durationSec)
                    },
                    color = Color.White, fontSize = 10.sp, fontWeight = if (v.liveNow) FontWeight.Bold else null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(if (v.liveNow) Hot else Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                )
                rank?.let {
                    Text("#$it", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(4.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "${v.channelTitle} • ${v.subs?.let { Fmt.count(it) + " subs" } ?: "subs hidden"}",
                    fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip("👁 ${Fmt.count(v.views)}")
                    if (v.liveNow && v.concurrentViewers != null) Chip("🔴 ${Fmt.count(v.concurrentViewers)} live", Hot.copy(alpha = .18f), Hot)
                    else Chip("⚡ ${Fmt.count(s.viewsPerHour.toLong())}/h")
                    s.outlier?.let { if (it >= 1) Chip("${"%.1f".format(it)}x", Hot.copy(alpha = .18f), Hot) }
                }
                Spacer(Modifier.height(2.dp))
                Text("${s.label} • ${Fmt.age(s.ageHours)} pehle", fontSize = 11.sp, color = scoreColor(s.score), fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreBadge(s.score)
                IconButton(onClick = onTrack) {
                    Icon(if (tracked) Icons.Filled.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                        contentDescription = "Track", tint = if (tracked) Hot else Muted)
                }
            }
        }
    }
}

@Composable
fun Meter(label: String, value: Double, color: Color, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row { Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text(detail, fontSize = 12.sp, color = Muted) }
        LinearProgressIndicator(
            progress = { value.toFloat().coerceIn(0f, 1f) }, color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
    }
}

@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Muted)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

/** Minimal line chart. */
@Composable
fun LineChart(points: List<Pair<Long, Double>>, color: Color, modifier: Modifier = Modifier) {
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Graph ke liye kam se kam 2 data points chahiye", fontSize = 12.sp, color = Muted)
        }
        return
    }
    val grid = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val minX = points.first().first.toDouble(); val maxX = points.last().first.toDouble()
        val minY = points.minOf { it.second }; val maxY = points.maxOf { it.second }
        val ry = (maxY - minY).takeIf { it > 0 } ?: 1.0
        val rx = (maxX - minX).takeIf { it > 0 } ?: 1.0
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            val px = ((x - minX) / rx * size.width).toFloat()
            val py = (size.height - (y - minY) / ry * size.height * 0.9 - size.height * 0.05).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(width = 5f))
    }
}

@Composable
fun BarRow(items: List<Pair<String, Int>>, color: Color) {
    val max = items.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    items.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(k, fontSize = 12.sp, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f).height(12.dp)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(v.toFloat() / max).clip(RoundedCornerShape(3.dp)).background(color))
            }
            Text(" $v", fontSize = 11.sp, color = Muted, modifier = Modifier.width(32.dp))
        }
    }
}

@Composable
fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}
