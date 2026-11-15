package com.gk.channelradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.gk.channelradar.data.ChannelResult
import com.gk.channelradar.data.ScoredVideo
import com.gk.channelradar.data.Video

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun ScoreBadge(score: Double, modifier: Modifier = Modifier) {
    val c = scoreColor(score)
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%.0f".format(score), color = c, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 18.sp)
            Text("score", color = c, fontSize = 9.sp, lineHeight = 10.sp)
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, bg: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Text(
        text, fontSize = 11.sp, color = color, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = valueColor, maxLines = 1)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChannelCard(
    r: ChannelResult,
    watched: Boolean,
    onClick: () -> Unit,
    onWatch: () -> Unit,
) {
    val best = r.best
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = r.channel.thumb, contentDescription = null,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.channel.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                        )
                        if (r.isNew) {
                            Spacer(Modifier.width(6.dp))
                            Tag("NEW", color = Color.White, bg = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        "${fmtCount(r.channel.subs)} subs · ${r.hits.size} hit${if (r.hits.size > 1) "s" else ""}" +
                            (r.niches.firstOrNull()?.let { " · $it" } ?: ""),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                    )
                }
                IconButton(onClick = onWatch) {
                    Icon(
                        if (watched) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Watchlist",
                        tint = if (watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ScoreBadge(r.score)
            }
            Spacer(Modifier.height(10.dp))
            VideoRow(best)
            if (r.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    r.tags.forEach { Tag(it) }
                }
            }
        }
    }
}

@Composable
fun VideoRow(sv: ScoredVideo, onClick: (() -> Unit)? = null) {
    val v = sv.video
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it },
        verticalAlignment = Alignment.Top
    ) {
        Thumb(v)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("👁 ${fmtCount(v.views)}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("${fmtX(sv.ratio)} subs", fontSize = 12.sp, color = GoodGreen, fontWeight = FontWeight.SemiBold)
                sv.outlier?.let { Text("${fmtX(it)} avg", fontSize = 12.sp, color = WarnAmber, fontWeight = FontWeight.SemiBold) }
            }
            Text(
                "${fmtCount(sv.viewsPerHour)}/hr · ${ago(v.publishedAt)}",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun Thumb(v: Video, width: Int = 128) {
    Box {
        AsyncImage(
            model = v.thumb, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(width.dp)
                .height((width * 9 / 16).dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        val label = if (v.isShort) "SHORT" else fmtDuration(v.durationSec)
        if (label.isNotBlank()) {
            Text(
                label, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (v.isShort) Color(0xCCFF3D3D) else Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

/** Bar chart of views for a list of videos (oldest → newest) with a dashed baseline. */
@Composable
fun ViewsBarChart(videos: List<Video>, baseline: Long?, subs: Long, highlight: Set<String>, modifier: Modifier = Modifier) {
    val data = videos.sortedBy { it.publishedAt }
    val maxV = (data.maxOfOrNull { it.views } ?: 1L).coerceAtLeast(1L).toFloat()
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val hiColor = MaterialTheme.colorScheme.primary
    val lineColor = WarnAmber
    val subColor = GoodGreen
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        if (data.isEmpty()) return@Canvas
        val gap = 4f
        val w = (size.width - gap * (data.size - 1)) / data.size
        data.forEachIndexed { i, v ->
            val h = (v.views / maxV) * size.height
            drawRoundRect(
                color = if (v.id in highlight) hiColor else barColor,
                topLeft = Offset(i * (w + gap), size.height - h),
                size = Size(w, h.coerceAtLeast(2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
            )
        }
        fun hLine(value: Long, c: Color) {
            if (value <= 0 || value > maxV) return
            val y = size.height - (value / maxV) * size.height
            drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        baseline?.let { hLine(it, lineColor) }
        hLine(subs, subColor)
    }
}

/** Small line chart for subscriber history. */
@Composable
fun Sparkline(values: List<Long>, modifier: Modifier = Modifier, color: Color = GoodGreen) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val mn = values.min().toFloat()
        val mx = values.max().toFloat()
        val range = (mx - mn).takeIf { it > 0 } ?: 1f
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = i * size.width / (values.size - 1)
            val y = size.height - (v - mn) / range * size.height
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(width = 4f))
    }
}
