package com.gk.metrosaathi

import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

// ======================================================================== Map tab

/** The Map tab: "Running" and "Upcoming" sub-tabs, each with a map or a list. */
@Composable
fun MapTab(state: AppState) {
    var sub by remember { mutableIntStateOf(0) }       // 0 = running, 1 = upcoming
    var listView by remember { mutableStateOf(false) }
    var diagram by remember { mutableStateOf(true) }
    val upcomingCount = state.data.stations.values.count { it.lines.isEmpty() }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            Tab(sub == 0, onClick = { sub = 0 }, text = { Text("Running (${state.data.operationalStations.size})") })
            Tab(sub == 1, onClick = { sub = 1 }, text = { Text("Upcoming ($upcomingCount)") })
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = !listView, onClick = { listView = false }, label = { Text("Map") })
            FilterChip(selected = listView, onClick = { listView = true }, label = { Text("List") })
            Spacer(Modifier.weight(1f))
            if (!listView) {
                FilterChip(selected = diagram, onClick = { diagram = true }, label = { Text("Diagram") })
                FilterChip(selected = !diagram, onClick = { diagram = false }, label = { Text("Real map") })
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                !listView -> NetworkMap(state, upcomingMode = sub == 1, diagram = diagram)
                sub == 0 -> RunningList(state)
                else -> UpcomingList(state)
            }
        }
    }
}

@Composable
private fun RunningList(state: AppState) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { state.data.search(query) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search stations…") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Text(
            "${results.size} running stations • tap for details",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.name }) { st -> StationRow(state, st) { state.sheetStation = st.name } }
        }
    }
}

@Composable
private fun UpcomingList(state: AppState) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "These stations are under construction. Names and dates may change.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        state.data.upcomingLines.forEach { line ->
            item(key = "h_" + line.id) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(8.dp).height(40.dp).clip(RoundedCornerShape(4.dp)).background(line.composeColor()))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(line.name, fontWeight = FontWeight.Bold)
                        Text(line.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(line.stations, key = { line.id + "_" + it }) { name ->
                val st = state.data.stations.getValue(name)
                val existing = st.lines.isNotEmpty()
                Row(
                    Modifier.fillMaxWidth().clickable { state.sheetStation = name }
                        .padding(start = 36.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, modifier = Modifier.weight(1f), fontWeight = if (existing) FontWeight.SemiBold else FontWeight.Normal)
                    Text(
                        if (existing) "Running – will be interchange" else "New station",
                        fontSize = 11.sp,
                        color = if (existing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ======================================================================== Map canvas

private class Projection(points: Collection<Pair<Float, Float>>) {
    val minX = points.minOf { it.first }
    val maxX = points.maxOf { it.first }
    val minY = points.minOf { it.second }
    val maxY = points.maxOf { it.second }
    val w = (maxX - minX).coerceAtLeast(0.0001f)
    val h = (maxY - minY).coerceAtLeast(0.0001f)
}

/**
 * Zoomable network map.
 * @param upcomingMode true = highlight under-construction lines, false = running network only
 * @param diagram true = DMRC-style schematic, false = geographic positions
 */
@Composable
fun NetworkMap(state: AppState, upcomingMode: Boolean, diagram: Boolean) {
    val data = state.data
    val context = LocalContext.current
    val density = LocalDensity.current

    // World coordinates per station for the chosen style (y grows downwards).
    val world: Map<String, Pair<Float, Float>> = remember(diagram) {
        if (diagram) {
            data.stations.values.associate { it.name to (it.sx to it.sy) }
        } else {
            val lat0 = data.stations.values.map { it.lat }.average()
            val k = cos(Math.toRadians(lat0)).toFloat()
            data.stations.values.associate { it.name to ((it.lon.toFloat() * k) to (-it.lat.toFloat())) }
        }
    }
    val proj = remember(world) { Projection(world.values) }

    // In the diagram, labels next to horizontal track are drawn diagonally (like the DMRC map).
    val rotateLabel: Set<String> = remember(world) {
        val res = mutableSetOf<String>()
        for (st in data.stations.values) {
            val (x, y) = world.getValue(st.name)
            var horizontal = 0
            var other = 0
            for (l in data.lines) {
                val i = l.stations.indexOf(st.name)
                if (i < 0) continue
                for (j in listOf(i - 1, i + 1)) {
                    val n = l.stations.getOrNull(j) ?: continue
                    val (nx, ny) = world.getValue(n)
                    if (abs(ny - y) < abs(nx - x) * 0.4f) horizontal++ else other++
                }
            }
            if (horizontal > 0 && other == 0) res += st.name
        }
        res
    }

    var scale by remember(diagram) { mutableFloatStateOf(1f) }
    var offset by remember(diagram) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var highlight by remember { mutableStateOf<String?>(null) }
    var initialised by remember(diagram) { mutableStateOf(false) }

    fun base(): Float = if (size.width == 0) 1f else min(size.width / proj.w, size.height / proj.h) * 0.92f

    fun screen(name: String): Offset {
        val (x, y) = world.getValue(name)
        return Offset((x - proj.minX) * base() * scale + offset.x, (y - proj.minY) * base() * scale + offset.y)
    }

    fun resetView() {
        scale = 1f
        val b = base()
        offset = Offset((size.width - proj.w * b) / 2f, (size.height - proj.h * b) / 2f)
    }

    fun centerOn(name: String, newScale: Float) {
        scale = newScale
        val b = base()
        val (x, y) = world.getValue(name)
        offset = Offset(size.width / 2f - (x - proj.minX) * b * newScale, size.height / 2f - (y - proj.minY) * b * newScale)
    }

    fun zoomBy(f: Float, center: Offset) {
        val ns = (scale * f).coerceIn(0.8f, 30f)
        val k = ns / scale
        offset = (offset - center) * k + center
        scale = ns
    }

    LaunchedEffect(size, diagram) {
        if (!initialised && size != IntSize.Zero) {
            resetView()
            initialised = true
        }
    }

    val (locating, locate) = rememberNearestFinder(data) { res ->
        val st = res?.firstOrNull()?.first
        if (st == null) {
            Toast.makeText(context, "Couldn't get your location", Toast.LENGTH_SHORT).show()
        } else {
            highlight = st.name
            centerOn(st.name, if (diagram) 4f else 8f)
            state.sheetStation = st.name
        }
    }

    val bg = MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onSurface
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val haloPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val hitRadius = with(density) { 26.dp.toPx() }

    // Which stations are shown / tappable in this mode.
    fun visible(st: Station) = if (upcomingMode) true else st.lines.isNotEmpty()
    fun emphasised(st: Station) = if (upcomingMode) st.upcomingLines.isNotEmpty() else true

    Box(Modifier.fillMaxSize().background(bg)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { size = it }
                .pointerInput(diagram) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (zoom != 1f) zoomBy(zoom, centroid)
                        offset += pan
                    }
                }
                .pointerInput(diagram, upcomingMode) {
                    detectTapGestures(
                        onDoubleTap = { p -> zoomBy(2f, p) },
                        onTap = { p ->
                            val hit = data.stations.values
                                .filter { visible(it) }
                                .map { it to (screen(it.name) - p).getDistance() }
                                .minByOrNull { it.second }
                            if (hit != null && hit.second < hitRadius) {
                                highlight = hit.first.name
                                state.sheetStation = hit.first.name
                            }
                        },
                    )
                },
        ) {
            val lineW = (if (diagram) 5.dp.toPx() else 4.dp.toPx()) * (1f + (scale - 1f) * 0.08f).coerceIn(1f, 1.8f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))
            val faded = Color(0xFF9E9E9E).copy(alpha = 0.35f)

            fun drawLineRun(l: MetroLine, color: Color, width: Float, dashed: Boolean) {
                val path = Path()
                l.stations.forEachIndexed { i, n ->
                    val p = screen(n)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(
                    path, color,
                    style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (dashed) dash else null),
                )
            }

            if (upcomingMode) {
                // Running network as faded context, upcoming lines bold on top.
                data.operationalLines.forEach { drawLineRun(it, faded, lineW * 0.8f, false) }
                data.connectors.forEach { drawLine(faded, screen(it.a), screen(it.b), lineW * 0.8f, StrokeCap.Round) }
                data.upcomingLines.forEach { drawLineRun(it, it.composeColor(), lineW * 1.1f, true) }
            } else {
                data.operationalLines.forEach { drawLineRun(it, it.composeColor(), lineW, false) }
                data.connectors.forEach {
                    val col = data.lineById[it.line]?.composeColor() ?: Color.Gray
                    drawLine(col, screen(it.a), screen(it.b), lineW, StrokeCap.Round)
                }
            }

            // Stations + labels
            val r = (if (diagram) 3.6.dp else 3.2.dp).toPx()
            val rIc = 5.5.dp.toPx()
            val margin = 60f
            val labelAll = if (diagram) scale > 2.2f else scale > 4.5f
            val labelIc = if (diagram) scale > 1.2f else scale > 1.7f
            labelPaint.textSize = 11.sp.toPx()
            labelPaint.color = onBg.toArgb()
            haloPaint.textSize = labelPaint.textSize
            haloPaint.strokeWidth = 3.dp.toPx()
            haloPaint.color = bg.toArgb()

            for (st in data.stations.values) {
                if (!visible(st)) continue
                val p = screen(st.name)
                if (p.x < -margin || p.y < -margin || p.x > this.size.width + margin || p.y > this.size.height + margin) continue
                val strong = emphasised(st)
                val ic = st.isInterchange || (upcomingMode && st.lines.isNotEmpty() && st.upcomingLines.isNotEmpty())
                val lineId = if (upcomingMode) st.upcomingLines.firstOrNull() ?: st.lines.firstOrNull()
                else st.lines.firstOrNull()
                val col = data.lineById[lineId ?: ""]?.composeColor() ?: Color.Gray
                val radius = if (ic) rIc else r
                if (!strong) {
                    drawCircle(faded, radius * 0.7f, p)
                    continue
                }
                drawCircle(Color.White, radius, p)
                drawCircle(if (ic) Color.Black else col, radius, p, style = Stroke(width = if (ic) 2.dp.toPx() else 1.8.dp.toPx()))
                if (st.name == highlight) {
                    drawCircle(Color(0xFFFF6D00), radius + 6.dp.toPx(), p, style = Stroke(width = 3.dp.toPx()))
                }
                val showLabel = labelAll || (labelIc && ic) || st.name == highlight ||
                    (upcomingMode && scale > 1.4f)
                if (showLabel) {
                    labelPaint.typeface = if (ic) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    haloPaint.typeface = labelPaint.typeface
                    val gap = radius + 4.dp.toPx()
                    drawIntoCanvas {
                        val c = it.nativeCanvas
                        if (diagram && st.name in rotateLabel) {
                            c.save()
                            c.rotate(-45f, p.x, p.y)
                            c.drawText(st.name, p.x + gap, p.y + labelPaint.textSize / 3f, haloPaint)
                            c.drawText(st.name, p.x + gap, p.y + labelPaint.textSize / 3f, labelPaint)
                            c.restore()
                        } else {
                            c.drawText(st.name, p.x + gap, p.y + labelPaint.textSize / 3f, haloPaint)
                            c.drawText(st.name, p.x + gap, p.y + labelPaint.textSize / 3f, labelPaint)
                        }
                    }
                }
            }
        }

        // Legend for upcoming mode
        if (upcomingMode) {
            Column(
                Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(bg.copy(alpha = 0.9f), RoundedCornerShape(8.dp)).padding(8.dp),
            ) {
                data.upcomingLines.forEach { l ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.width(16.dp).height(4.dp).background(l.composeColor()))
                        Spacer(Modifier.width(6.dp))
                        Text(l.name, fontSize = 11.sp)
                    }
                }
            }
        }

        Text(
            "Pinch or double-tap to zoom • tap a station",
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
