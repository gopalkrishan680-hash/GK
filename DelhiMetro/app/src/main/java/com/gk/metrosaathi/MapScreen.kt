package com.gk.metrosaathi

import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min

/** Geographic projection of the whole network into "world units" (degrees, lon scaled by cos(lat)). */
private class Projection(data: MetroData) {
    private val all = data.stations.values
    val minLat = all.minOf { it.lat }
    val maxLat = all.maxOf { it.lat }
    val minLon = all.minOf { it.lon }
    val maxLon = all.maxOf { it.lon }
    private val cosLat = cos(Math.toRadians((minLat + maxLat) / 2))
    val worldW = ((maxLon - minLon) * cosLat).toFloat()
    val worldH = (maxLat - minLat).toFloat()
    fun wx(lon: Double) = ((lon - minLon) * cosLat).toFloat()
    fun wy(lat: Double) = (maxLat - lat).toFloat()
}

@Composable
fun MapScreen(state: AppState) {
    val data = state.data
    val context = LocalContext.current
    val density = LocalDensity.current
    val proj = remember(data) { Projection(data) }

    var showUpcoming by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var highlight by remember { mutableStateOf<String?>(null) }

    fun base(): Float = if (size.width == 0) 1f else min(size.width / proj.worldW, size.height / proj.worldH) * 0.92f

    fun screen(st: Station): Offset = Offset(
        proj.wx(st.lon) * base() * scale + offset.x,
        proj.wy(st.lat) * base() * scale + offset.y,
    )

    fun resetView() {
        scale = 1f
        val b = base()
        offset = Offset((size.width - proj.worldW * b) / 2f, (size.height - proj.worldH * b) / 2f)
    }

    fun centerOn(st: Station, newScale: Float) {
        scale = newScale
        val b = base()
        offset = Offset(
            size.width / 2f - proj.wx(st.lon) * b * newScale,
            size.height / 2f - proj.wy(st.lat) * b * newScale,
        )
    }

    fun zoomBy(f: Float, center: Offset) {
        val ns = (scale * f).coerceIn(0.8f, 30f)
        val k = ns / scale
        offset = (offset - center) * k + center
        scale = ns
    }

    val (locating, locate) = rememberNearestFinder(data) { res ->
        val st = res?.firstOrNull()?.first
        if (st == null) {
            Toast.makeText(context, "Location nahi mili", Toast.LENGTH_SHORT).show()
        } else {
            highlight = st.name
            centerOn(st, 8f)
            state.sheetStation = st.name
        }
    }

    val bg = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onSurface
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val haloPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val hitRadius = with(density) { 26.dp.toPx() }

    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    val first = size == IntSize.Zero
                    size = it
                    if (first) resetView()
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f) zoomBy(zoom, centroid)
                        offset += pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { p -> zoomBy(2f, p) },
                        onTap = { p ->
                            val hit = data.stations.values
                                .filter { showUpcoming || it.lines.isNotEmpty() }
                                .map { it to (screen(it) - p).getDistance() }
                                .minByOrNull { it.second }
                            if (hit != null && hit.second < hitRadius) {
                                highlight = hit.first.name
                                state.sheetStation = hit.first.name
                            }
                        },
                    )
                },
        ) {
            val line = 4.dp.toPx() * (1f + (scale - 1f) * 0.08f).coerceIn(1f, 1.8f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

            // Upcoming first (underneath), then operational.
            val toDraw = (if (showUpcoming) data.upcomingLines else emptyList()) + data.operationalLines
            for (l in toDraw) {
                val path = Path()
                l.stations.forEachIndexed { i, n ->
                    val p = screen(data.stations.getValue(n))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path,
                    color = l.composeColor().copy(alpha = if (l.upcoming) 0.55f else 1f),
                    style = Stroke(
                        width = if (l.upcoming) line * 0.75f else line,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (l.upcoming) dash else null,
                    ),
                )
            }
            for (c in data.connectors) {
                val col = data.lineById[c.line]?.composeColor() ?: Color.Gray
                drawLine(col, screen(data.stations.getValue(c.a)), screen(data.stations.getValue(c.b)), line, StrokeCap.Round)
            }

            // Stations
            val r = 3.2.dp.toPx()
            val rIc = 5.dp.toPx()
            val margin = 40f
            val showAllLabels = scale > 4.5f
            val showIcLabels = scale > 1.7f
            labelPaint.textSize = 11.sp.toPx()
            labelPaint.color = onBg.toArgb()
            haloPaint.textSize = labelPaint.textSize
            haloPaint.strokeWidth = 3.dp.toPx()
            haloPaint.color = bg.toArgb()

            for (st in data.stations.values) {
                val upcomingOnly = st.lines.isEmpty()
                if (upcomingOnly && !showUpcoming) continue
                val p = screen(st)
                if (p.x < -margin || p.y < -margin || p.x > this.size.width + margin || p.y > this.size.height + margin) continue
                val ic = st.isInterchange
                val mainLine = data.lineById[(st.lines.firstOrNull() ?: st.upcomingLines.firstOrNull()) ?: ""]
                val radius = if (ic) rIc else r
                drawCircle(Color.White, radius, p)
                drawCircle(
                    if (ic) Color.Black else (mainLine?.composeColor() ?: Color.Gray).copy(alpha = if (upcomingOnly) 0.6f else 1f),
                    radius, p, style = Stroke(width = if (ic) 2.dp.toPx() else 1.6.dp.toPx()),
                )
                if (st.name == highlight) {
                    drawCircle(Color(0xFFFF6D00), radius + 6.dp.toPx(), p, style = Stroke(width = 3.dp.toPx()))
                }
                if (showAllLabels || (showIcLabels && ic) || st.name == highlight) {
                    labelPaint.typeface = if (ic) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    haloPaint.typeface = labelPaint.typeface
                    val tx = p.x + radius + 4.dp.toPx()
                    val ty = p.y + labelPaint.textSize / 3f
                    drawIntoCanvas {
                        it.nativeCanvas.drawText(st.name, tx, ty, haloPaint)
                        it.nativeCanvas.drawText(st.name, tx, ty, labelPaint)
                    }
                }
            }
        }

        // Overlay controls
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = showUpcoming,
                onClick = { showUpcoming = !showUpcoming },
                label = { Text("Aane wali lines") },
            )
        }
        Text(
            "Pinch/double-tap se zoom • station par tap",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(bg.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Column(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            SmallFloatingActionButton(onClick = { zoomBy(1.6f, center) }) { Icon(Icons.Filled.Add, "Zoom in") }
            SmallFloatingActionButton(onClick = { zoomBy(1 / 1.6f, center) }) { Icon(Icons.Filled.Remove, "Zoom out") }
            SmallFloatingActionButton(onClick = { resetView(); highlight = null }) { Icon(Icons.Filled.CenterFocusStrong, "Reset") }
            SmallFloatingActionButton(onClick = { if (!locating) locate() }) {
                if (locating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.MyLocation, "Nearest station")
            }
        }
    }
}
