package com.gk.procamera

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.MediaActionSound
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.PhotoFilter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

private val Accent = Color(0xFFFFCC00)
private val PanelBg = Color(0xAA000000)

@Composable
fun CameraScreen(shutterRequests: SharedFlow<Unit>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { CameraEngine(context.applicationContext) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val swatch = remember { sampleSwatch() }

    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val filter = Filters.all[filterIndex]
    var panel by remember { mutableStateOf(Panel.NONE) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var timer by rememberSaveable { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var thumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    val blink = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        onDispose {
            sound.release()
            engine.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        try {
            engine.init()
        } catch (e: Exception) {
            Toast.makeText(context, "Camera start nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(engine.ready, engine.lensFacing, engine.mode) {
        if (!engine.ready) return@LaunchedEffect
        try {
            engine.bind(lifecycleOwner, previewView.surfaceProvider)
        } catch (e: Exception) {
            Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        panel = if (engine.mode == CamMode.PRO) Panel.PRO else if (panel == Panel.PRO) Panel.NONE else panel
    }
    LaunchedEffect(engine.proIso, engine.proShutterNs, engine.proFocus, engine.proWb) { engine.applyPro() }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    // Keep saved photos upright even though the UI is locked to portrait.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                engine.setRotation(
                    when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                )
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    fun shoot() {
        if (busy || !engine.ready) return
        busy = true
        scope.launch {
            try {
                for (s in timer downTo 1) {
                    countdown = s
                    delay(1000)
                }
                countdown = 0
                sound.play(MediaActionSound.SHUTTER_CLICK)
                launch {
                    blink.snapTo(0.85f)
                    blink.animateTo(0f, tween(260))
                }
                val uri = engine.capture(filter) { status = it }
                if (uri != null) {
                    lastUri = uri
                    thumb = withContext(Dispatchers.IO) { ImageProcessing.loadThumbnail(context, uri)?.asImageBitmap() }
                } else {
                    Toast.makeText(context, "Photo save nahi hui", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                status = null
                countdown = 0
                busy = false
            }
        }
    }

    val shootNow by rememberUpdatedState { shoot() }
    LaunchedEffect(Unit) { shutterRequests.collect { shootNow() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // ------------------------------------------------------------ top bar
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (engine.hasFlash) {
                TopIcon(
                    when (engine.flashMode) {
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        else -> Icons.Filled.FlashOff
                    },
                    "Flash",
                    active = engine.flashMode != ImageCapture.FLASH_MODE_OFF,
                ) { engine.cycleFlash() }
            } else {
                Spacer(Modifier.size(48.dp))
            }
            TopIcon(
                when (timer) {
                    3 -> Icons.Filled.Timer3
                    10 -> Icons.Filled.Timer10
                    else -> Icons.Filled.TimerOff
                },
                "Timer",
                active = timer > 0,
            ) { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } }
            TopIcon(if (grid) Icons.Filled.GridOn else Icons.Filled.GridOff, "Grid", active = grid) { grid = !grid }
            TopIcon(Icons.Filled.Exposure, "Exposure", active = panel == Panel.EV) {
                panel = if (panel == Panel.EV) Panel.NONE else Panel.EV
            }
            if (engine.mode == CamMode.PRO) {
                TopIcon(Icons.Filled.Tune, "Pro controls", active = panel == Panel.PRO) {
                    panel = if (panel == Panel.PRO) Panel.NONE else Panel.PRO
                }
            }
            TopIcon(Icons.Filled.PhotoFilter, "Filters", active = panel == Panel.FILTERS || filter.matrix != null) {
                panel = if (panel == Panel.FILTERS) Panel.NONE else Panel.FILTERS
            }
        }

        // ------------------------------------------------------------ viewfinder
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize(),
                    update = { v ->
                        val m = filter.matrix
                        if (m == null) v.setLayerType(View.LAYER_TYPE_NONE, null)
                        else v.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
                    },
                )

                if (grid) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) {
                            val x = size.width * i / 3f
                            val y = size.height * i / 3f
                            drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.5f)
                            drawLine(c, Offset(0f, y), Offset(size.width, y), 1.5f)
                        }
                    }
                }

                // Gestures: tap to focus, pinch to zoom.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInputTransform { zoomChange -> engine.zoomTo(engine.zoom * zoomChange) }
                        .pointerInputTap { off ->
                            val p = previewView.meteringPointFactory.createPoint(off.x, off.y)
                            engine.focusAt(p)
                            focusPoint = off
                        }
                )

                focusPoint?.let { fp ->
                    val ringPx = with(LocalDensity.current) { 36.dp.toPx() }
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Accent, radius = ringPx, center = fp, style = Stroke(width = 3f))
                    }
                }

                // Black blink on capture, like the iPhone.
                if (blink.value > 0f) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blink.value)))
                }

                if (countdown > 0) {
                    Text(
                        "$countdown",
                        color = Color.White,
                        fontSize = 96.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // Mode badge
                val badge = when {
                    engine.mode == CamMode.NIGHT && engine.extensionActive -> "NIGHT • native"
                    engine.mode == CamMode.NIGHT -> "NIGHT • multi-frame"
                    engine.mode == CamMode.PRO && engine.manualSensor -> "PRO • manual"
                    engine.extensionActive -> engine.mode.label
                    else -> null
                }
                val badgeText = listOfNotNull(badge, filter.matrix?.let { filter.name.uppercase(Locale.US) })
                    .joinToString("  ·  ")
                if (badgeText.isNotEmpty()) {
                    Text(
                        badgeText,
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .background(Accent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }

                status?.let {
                    Row(
                        Modifier
                            .align(Alignment.Center)
                            .background(PanelBg, RoundedCornerShape(12.dp))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(it, color = Color.White, fontSize = 14.sp)
                    }
                }

                // Bottom overlays: zoom chips + active panel.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZoomChips(engine)
                    Spacer(Modifier.height(8.dp))
                    AnimatedVisibility(panel != Panel.NONE) {
                        Box(Modifier.fillMaxWidth().background(PanelBg).padding(vertical = 8.dp)) {
                            when (panel) {
                                Panel.FILTERS -> FilterStrip(thumb ?: swatch, filterIndex) { filterIndex = it }
                                Panel.EV -> EvPanel(engine)
                                Panel.PRO -> ProPanel(engine)
                                Panel.NONE -> Unit
                            }
                        }
                    }
                }
            }
        }

        // ------------------------------------------------------------ mode selector
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.width(16.dp))
            engine.modes.forEach { m ->
                Text(
                    m.label,
                    color = if (m == engine.mode) Accent else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (m == engine.mode) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { engine.mode = m }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
        }

        // ------------------------------------------------------------ shutter row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF222222))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable(enabled = lastUri != null) {
                        lastUri?.let { uri ->
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "Gallery app nahi mila", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            ) {
                thumb?.let {
                    Image(it, "Last photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }

            Box(
                Modifier
                    .size(78.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            busy -> Color.Gray
                            engine.mode == CamMode.NIGHT -> Accent
                            else -> Color.White
                        }
                    )
                    .clickable(enabled = !busy) { shoot() },
                contentAlignment = Alignment.Center,
            ) {
                if (timer > 0 && !busy) Icon(Icons.Filled.Timer, null, tint = Color.Black)
            }

            IconButton(
                onClick = { engine.switchLens() },
                enabled = !busy,
                modifier = Modifier.size(54.dp).background(Color(0xFF222222), CircleShape),
            ) {
                Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White)
            }
        }
    }
}

private enum class Panel { NONE, FILTERS, EV, PRO }

@Composable
private fun TopIcon(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, desc, tint = if (active) Accent else Color.White)
    }
}

private fun Modifier.pointerInputTransform(onZoom: (Float) -> Unit): Modifier =
    pointerInput("zoom") { detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) onZoom(zoom) } }

private fun Modifier.pointerInputTap(onTap: (Offset) -> Unit): Modifier =
    pointerInput("tap") { detectTapGestures(onTap = onTap) }

@Composable
private fun ZoomChips(engine: CameraEngine) {
    val stops = listOf(0.5f, 1f, 2f, 5f).filter { it >= engine.minZoom - 0.01f && it <= engine.maxZoom + 0.01f }
    if (stops.size < 2) return
    // The chip closest (from below) to the current zoom shows the live value.
    val activeStop = stops.lastOrNull { engine.zoom >= it - 0.05f } ?: stops.first()
    Row(
        Modifier.background(PanelBg, RoundedCornerShape(50)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stops.forEach { s ->
            val active = s == activeStop
            val label = if (active && abs(engine.zoom - s) > 0.05f) fmtZoom(engine.zoom) else fmtZoom(s)
            Box(
                Modifier
                    .size(if (active) 40.dp else 34.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable { engine.zoomTo(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) Accent else Color.White, fontSize = if (active) 12.sp else 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun fmtZoom(z: Float): String =
    if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}x" else String.format(Locale.US, "%.1fx", z)

@Composable
private fun FilterStrip(preview: ImageBitmap, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        itemsIndexed(Filters.all) { i, f ->
            val sel = i == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onSelect(i) },
            ) {
                Image(
                    bitmap = preview,
                    contentDescription = f.name,
                    contentScale = ContentScale.Crop,
                    colorFilter = f.matrix?.let { ColorFilter.colorMatrix(ComposeColorMatrix(it.array.copyOf())) },
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(if (sel) 2.dp else 0.5.dp, if (sel) Accent else Color.White.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(f.name, color = if (sel) Accent else Color.White, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EvPanel(engine: CameraEngine) {
    if (engine.evRange.first == engine.evRange.last) {
        Text("Exposure control is not supported", color = Color.White, modifier = Modifier.padding(12.dp))
        return
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        val ev = engine.evIndex * engine.evStep
        LabelRow("Exposure", String.format(Locale.US, "%+.1f EV", ev)) { engine.setEv(0) }
        Slider(
            value = engine.evIndex.toFloat(),
            onValueChange = { engine.setEv(it.roundToInt()) },
            valueRange = engine.evRange.first.toFloat()..engine.evRange.last.toFloat(),
            colors = sliderColors(),
        )
    }
}

@Composable
private fun ProPanel(engine: CameraEngine) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        val iso = engine.isoRange
        val sh = engine.shutterRange
        if (engine.manualSensor && iso != null && sh != null) {
            // ISO (log scale). Far left = Auto.
            val isoLo = iso.first.coerceAtLeast(25).toDouble()
            val isoHi = iso.last.toDouble().coerceAtLeast(isoLo + 1)
            LabelRow("ISO", if (engine.proIso == 0) "AUTO" else "${engine.proIso}") { engine.proIso = 0 }
            Slider(
                value = if (engine.proIso == 0) 0f else 0.06f + 0.94f * logPos(engine.proIso.toDouble(), isoLo, isoHi),
                onValueChange = { v ->
                    engine.proIso = if (v < 0.05f) 0 else logVal((v - 0.06f) / 0.94f, isoLo, isoHi).roundToInt()
                },
                colors = sliderColors(),
            )

            // Shutter speed (log scale), capped at 2 s for handheld sanity. Far left = Auto.
            val shLo = sh.first.coerceAtLeast(10_000L).toDouble()
            val shHi = sh.last.coerceAtMost(2_000_000_000L).toDouble().coerceAtLeast(shLo + 1)
            LabelRow("Shutter", if (engine.proShutterNs == 0L) "AUTO" else fmtShutter(engine.proShutterNs)) { engine.proShutterNs = 0L }
            Slider(
                value = if (engine.proShutterNs == 0L) 0f else 0.06f + 0.94f * logPos(engine.proShutterNs.toDouble(), shLo, shHi),
                onValueChange = { v ->
                    engine.proShutterNs = if (v < 0.05f) 0L else logVal((v - 0.06f) / 0.94f, shLo, shHi).roundToLong()
                },
                colors = sliderColors(),
            )

            if (engine.minFocusDist > 0f) {
                LabelRow("Focus", if (engine.proFocus < 0f) "AF" else if (engine.proFocus < 0.05f) "∞" else "MF") { engine.proFocus = -1f }
                Slider(
                    value = if (engine.proFocus < 0f) 0f else 0.06f + 0.94f * (engine.proFocus / engine.minFocusDist),
                    onValueChange = { v ->
                        engine.proFocus = if (v < 0.05f) -1f else ((v - 0.06f) / 0.94f).coerceIn(0f, 1f) * engine.minFocusDist
                    },
                    colors = sliderColors(),
                )
            }
        } else {
            Text("Is camera par manual ISO/Shutter support nahi hai — WB aur EV use karein.", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("WB", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
            WhiteBalanceModes.forEach { (name, value) ->
                val sel = engine.proWb == value
                Text(
                    name,
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) Accent else Color(0x33FFFFFF))
                        .clickable { engine.proWb = value }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun LabelRow(title: String, value: String, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Text(value, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onReset() })
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Accent,
    activeTrackColor = Accent,
    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
)

private fun logPos(v: Double, lo: Double, hi: Double): Float =
    ((ln(v.coerceIn(lo, hi)) - ln(lo)) / (ln(hi) - ln(lo))).toFloat()

private fun logVal(pos: Float, lo: Double, hi: Double): Double =
    exp(ln(lo) + (ln(hi) - ln(lo)) * pos.coerceIn(0f, 1f))

private fun fmtShutter(ns: Long): String =
    if (ns >= 400_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9)
    else "1/${(1e9 / ns).roundToInt()}"

/** Small colourful tile used to preview filters before the first photo is taken. */
private fun sampleSwatch(): ImageBitmap {
    val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val p = android.graphics.Paint()
    val colors = intArrayOf(0xFF4A90D9.toInt(), 0xFFF2C6A0.toInt(), 0xFF5BA55B.toInt(), 0xFFD9534F.toInt())
    colors.forEachIndexed { i, col ->
        p.color = col
        c.drawRect(0f, i * 16f, 64f, (i + 1) * 16f, p)
    }
    return b.asImageBitmap()
}
