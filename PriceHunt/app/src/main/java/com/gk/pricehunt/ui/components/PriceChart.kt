package com.gk.pricehunt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.pricehunt.data.db.PricePoint
import com.gk.pricehunt.util.rupees
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Step-line price history chart. Touch / drag to inspect individual points. */
@Composable
fun PriceChart(points: List<PricePoint>, currentPrice: Double, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    // always extend the line to "now" with the current price
    val data = remember(points, currentPrice) {
        val list = points.map { it.time to it.price }.toMutableList()
        val now = System.currentTimeMillis()
        if (list.isEmpty() || list.last().first < now - 60_000) list += now to currentPrice
        if (list.size == 1) list.add(0, (now - 86_400_000L) to list[0].second)
        list
    }
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val fmt = remember { SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH) }
    val dayFmt = remember { SimpleDateFormat("d MMM", Locale.ENGLISH) }

    val minP = data.minOf { it.second }
    val maxP = data.maxOf { it.second }
    val pad = ((maxP - minP) * 0.15).coerceAtLeast(maxP * 0.03).coerceAtLeast(1.0)
    val lo = (minP - pad).coerceAtLeast(0.0)
    val hi = maxP + pad
    val t0 = data.first().first
    val t1 = data.last().first.coerceAtLeast(t0 + 1)

    Column(modifier) {
        val sel = selected?.let { data[it] }
        Text(
            if (sel != null) "${rupees(sel.second)}  ·  ${fmt.format(Date(sel.first))}" else "Tap the chart to see a price",
            fontSize = 13.sp, fontWeight = if (sel != null) FontWeight.Bold else FontWeight.Normal,
            color = if (sel != null) MaterialTheme.colorScheme.onSurface else label,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(data) {
                    fun pick(x: Float) {
                        val t = t0 + ((x / size.width).coerceIn(0f, 1f) * (t1 - t0)).toLong()
                        selected = data.indices.minByOrNull { kotlin.math.abs(data[it].first - t) }
                    }
                    detectTapGestures { pick(it.x) }
                }
                .pointerInput(data) {
                    detectDragGestures { change, _ ->
                        val t = t0 + ((change.position.x / size.width).coerceIn(0f, 1f) * (t1 - t0)).toLong()
                        selected = data.indices.minByOrNull { kotlin.math.abs(data[it].first - t) }
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = ((t - t0).toFloat() / (t1 - t0)) * w
            fun y(p: Double) = (h - ((p - lo) / (hi - lo)) * h).toFloat()

            // grid lines: min & max
            listOf(minP, maxP).forEach { p ->
                drawLine(grid, Offset(0f, y(p)), Offset(w, y(p)), strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }

            val path = Path()
            data.forEachIndexed { i, (t, p) ->
                if (i == 0) path.moveTo(x(t), y(p))
                else { path.lineTo(x(t), y(data[i - 1].second)); path.lineTo(x(t), y(p)) }
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 5f))
            data.forEach { (t, p) -> drawCircle(line, 5f, Offset(x(t), y(p))) }

            selected?.let { i ->
                val (t, p) = data[i]
                drawLine(label, Offset(x(t), 0f), Offset(x(t), h), strokeWidth = 2f)
                drawCircle(Color.White, 11f, Offset(x(t), y(p)))
                drawCircle(line, 8f, Offset(x(t), y(p)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(dayFmt.format(Date(t0)), fontSize = 11.sp, color = label, modifier = Modifier.weight(1f))
            Text("Today", fontSize = 11.sp, color = label)
        }
    }
}
