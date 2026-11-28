package com.gk.procam.ui

import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gk.procam.CameraEngine
import com.gk.procam.Mode
import com.gk.procam.PhotoStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

val Yellow = Color(0xFFFFD60A)
val RecRed = Color(0xFFFF3B30)
val Glass = Color(0x66000000)
val GlassLight = Color(0x33FFFFFF)

@Composable
fun CameraScreen(e: CameraEngine) {
    val s = e.settings
    var showSettings by remember { mutableStateOf(false) }
    var trayOpen by remember { mutableStateOf(false) }
    var trayItem by remember { mutableStateOf<TrayItem?>(null) }
    var apertureOpen by remember { mutableStateOf(false) }
    val iconRotation = rememberContinuousRotation(-e.snappedRotation.toFloat())

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.statusBarsPadding())
            TopBar(e, trayOpen, iconRotation,
                onTray = { trayOpen = !trayOpen; trayItem = null },
                onSettings = { showSettings = true })

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val tall = e.mode.isVideo || s.aspect == 1
                val ratio = if (tall) 9f / 16f else 3f / 4f
                Box(
                    Modifier.align(Alignment.Center).aspectRatio(ratio).clipToBounds()
                ) {
                    CameraPreview(e)
                    GestureLayer(e)
                    if (s.aspect == 2 && !e.mode.isVideo) SquareMask()
                    if (s.grid) GridOverlay()
                    if (s.level) LevelOverlay(e.orientation, e.snappedRotation)
                    FocusOverlay(e)
                    BlinkOverlay(e.shutterBlink)
                    CenterMessages(e)
                    TopBadges(e, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
                    if (e.mode == Mode.PRO) {
                        Histogram(e.histogram, Modifier.align(Alignment.TopStart).padding(10.dp))
                    }
                    if (e.mode == Mode.PORTRAIT) {
                        ApertureButton(e, iconRotation, Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                            apertureOpen = !apertureOpen
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        e.qrText?.let { QrPill(it) { e.openQr() } }
                        Spacer(Modifier.height(8.dp))
                        if (e.mode == Mode.PORTRAIT && apertureOpen) {
                            ApertureSlider(e); Spacer(Modifier.height(8.dp))
                        }
                        if (!e.isRecording || e.lensOptions.size > 1) ZoomPills(e, iconRotation)
                        if (e.mode == Mode.PRO) { Spacer(Modifier.height(8.dp)); ProPanel(e) }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                when {
                    e.isRecording -> {}
                    trayOpen -> OptionsTray(e, trayItem) { trayItem = if (trayItem == it) null else it }
                    else -> ModeCarousel(e)
                }
            }
            BottomBar(e, iconRotation)
            Spacer(Modifier.navigationBarsPadding().height(8.dp))
        }

        AnimatedVisibility(showSettings, enter = fadeIn(), exit = fadeOut()) {
            SettingsSheet(e) { showSettings = false; e.rebind() }
        }
    }
}

/** Animates rotation along the shortest path so 270 -> 0 doesn't spin the long way round. */
@Composable
fun rememberContinuousRotation(target: Float): Float {
    var acc by remember { mutableFloatStateOf(target) }
    val cur = acc
    var diff = (target - cur) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    acc = cur + diff
    val anim by animateFloatAsState(acc, tween(300), label = "rot")
    return anim
}

// ---------------------------------------------------------------------------------- preview

@Composable
private fun CameraPreview(e: CameraEngine) {
    val style = e.style
    AndroidView(
        factory = { e.previewView },
        modifier = Modifier.fillMaxSize(),
        update = { v ->
            if (Build.VERSION.SDK_INT >= 31) {
                val cm = style.colorMatrix()
                v.setRenderEffect(cm?.let { RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(it)) })
            }
        },
    )
}

@Composable
private fun GestureLayer(e: CameraEngine) {
    Box(
        Modifier.fillMaxSize()
            .pointerInput(e.mode) {
                detectTapGestures(
                    onTap = { e.tapFocus(it.x, it.y, lock = false) },
                    onLongPress = { e.tapFocus(it.x, it.y, lock = true) },
                )
            }
            .pointerInput(e.mode) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var total = Offset.Zero
                    var kind = 0 // 1 horizontal swipe, 2 vertical exposure drag, 3 pinch
                    val startEv = e.ev
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            kind = 3
                            val z = event.calculateZoom()
                            if (z != 1f) e.zoomBy(z)
                            event.changes.forEach { it.consume() }
                        } else if (kind != 3) {
                            val ch = event.changes.first()
                            total += ch.positionChange()
                            if (kind == 0 && total.getDistance() > viewConfiguration.touchSlop * 1.5f) {
                                kind = if (abs(total.x) > abs(total.y)) 1 else 2
                            }
                            if (kind == 2 && e.focusPoint != null && e.mode != Mode.PRO) {
                                e.applyEv(startEv - total.y / size.height * 4f)
                                ch.consume()
                            }
                            if (kind == 1) ch.consume()
                        }
                    } while (event.changes.any { it.pressed })
                    if (kind == 1 && abs(total.x) > size.width * 0.12f) e.shiftMode(if (total.x < 0) 1 else -1)
                }
            }
    )
}

@Composable
private fun SquareMask() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bar = (maxHeight - maxWidth) / 2
        Box(Modifier.fillMaxWidth().height(bar).background(Color.Black).align(Alignment.TopCenter))
        Box(Modifier.fillMaxWidth().height(bar).background(Color.Black).align(Alignment.BottomCenter))
    }
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.dp.toPx())
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.dp.toPx())
        }
    }
}

@Composable
private fun LevelOverlay(orientation: Int, snapped: Int) {
    var tilt = (orientation - snapped) % 360
    if (tilt > 180) tilt -= 360
    if (tilt < -180) tilt += 360
    if (abs(tilt) > 10) return
    val level = abs(tilt) <= 1
    val color = if (level) Yellow else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val seg = 28.dp.toPx()
        val gap = 34.dp.toPx()
        val stroke = 1.5.dp.toPx()
        // Fixed reference ticks follow the snapped device orientation…
        rotate(-snapped.toFloat(), Offset(cx, cy)) {
            drawLine(color.copy(alpha = 0.9f), Offset(cx - gap - seg, cy), Offset(cx - gap, cy), stroke)
            drawLine(color.copy(alpha = 0.9f), Offset(cx + gap, cy), Offset(cx + gap + seg, cy), stroke)
        }
        // …while the centre line stays parallel to the real horizon.
        rotate(if (level) -snapped.toFloat() else -orientation.toFloat(), Offset(cx, cy)) {
            drawLine(color, Offset(cx - gap + 4.dp.toPx(), cy), Offset(cx + gap - 4.dp.toPx(), cy), stroke)
        }
    }
}

@Composable
private fun FocusOverlay(e: CameraEngine) {
    val p = e.focusPoint ?: return
    val key = e.focusKey
    val scale = remember(key) { Animatable(1.5f) }
    val alpha = remember(key) { Animatable(1f) }
    LaunchedEffect(key) {
        scale.animateTo(1f, tween(220))
        if (!e.aeLocked) {
            delay(2600)
            alpha.animateTo(0.45f, tween(400))
            delay(1800)
            e.clearFocus()
        }
    }
    val density = LocalDensity.current
    val boxPx = with(density) { 76.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((p.x - boxPx / 2).roundToInt(), (p.y - boxPx / 2).roundToInt()) }
            .size(76.dp)
            .scale(scale.value)
            .alpha(alpha.value)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 1.2.dp.toPx()
            drawRect(Yellow, style = Stroke(st))
            val tick = 6.dp.toPx()
            drawLine(Yellow, Offset(size.width / 2, 0f), Offset(size.width / 2, tick), st)
            drawLine(Yellow, Offset(size.width / 2, size.height), Offset(size.width / 2, size.height - tick), st)
            drawLine(Yellow, Offset(0f, size.height / 2), Offset(tick, size.height / 2), st)
            drawLine(Yellow, Offset(size.width, size.height / 2), Offset(size.width - tick, size.height / 2), st)
        }
        if (e.mode != Mode.PRO) {
            val range = (e.evMax - e.evMin).takeIf { it > 0f } ?: 1f
            val frac = (e.ev - e.evMin) / range
            Box(Modifier.align(Alignment.CenterEnd).offset(x = 24.dp).height(110.dp).width(20.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width / 2
                    val sunY = size.height * (1f - frac)
                    drawLine(Yellow.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, (sunY - 12.dp.toPx()).coerceAtLeast(0f)), 1.dp.toPx())
                    drawLine(Yellow.copy(alpha = 0.8f), Offset(x, (sunY + 12.dp.toPx()).coerceAtMost(size.height)), Offset(x, size.height), 1.dp.toPx())
                }
                Icon(Icons.Filled.WbSunny, null, tint = Yellow,
                    modifier = Modifier.size(18.dp).align(Alignment.TopCenter)
                        .offset { IntOffset(0, ((1f - frac) * with(density) { 92.dp.toPx() }).roundToInt()) })
            }
        }
    }
}

@Composable
private fun BlinkOverlay(trigger: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger == 0) return@LaunchedEffect
        a.snapTo(1f); a.animateTo(0f, tween(180))
    }
    if (a.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a.value)))
}

@Composable
private fun CenterMessages(e: CameraEngine) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (e.countdown > 0) {
            Text("${e.countdown}", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Thin)
        }
        if (e.burstCount > 0) {
            Text("${e.burstCount}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
        }
        if (e.capturing && e.captureLabel != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { e.captureProgress.coerceAtLeast(0.02f) },
                        color = Yellow, trackColor = Color.White.copy(alpha = 0.2f),
                        strokeWidth = 3.dp, modifier = Modifier.size(64.dp),
                    )
                    if (e.mode == Mode.NIGHT) Icon(Icons.Filled.NightsStay, null, tint = Yellow, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(e.captureLabel ?: "", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    val msg = e.toast
    LaunchedEffect(msg) { if (msg != null) { delay(2400); if (e.toast == msg) e.toast = null } }
    if (msg != null) {
        Box(Modifier.fillMaxSize().padding(top = 54.dp), contentAlignment = Alignment.TopCenter) {
            Text(msg, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC1C1C1E))
                    .padding(horizontal = 14.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun TopBadges(e: CameraEngine, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (e.aeLocked) Badge("AE/AF LOCK", Yellow, Color.Black)
        if (e.mode == Mode.SLOMO && e.slomoActiveFps > 0) Badge("${e.slomoActiveFps} FPS", Color(0x99000000), Color.White)
        if (e.mode == Mode.NIGHT) Badge(
            if (e.activeExtension != null) "NIGHT · DEVICE" else "NIGHT · PROCAM STACK", Color(0x99000000), Yellow
        )
        if (e.mode == Mode.PHOTO && e.settings.highRes) Badge("50 MP", Color(0x99000000), Color.White)
        if (e.mode == Mode.PRO && e.rawSupported && e.settings.proRaw) Badge("RAW", Color(0x99000000), Color.White)
    }
}

@Composable
fun Badge(text: String, bg: Color, fg: Color) {
    Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
private fun QrPill(text: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Yellow).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.QrCode2, null, tint = Color.Black, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
    }
}

@Composable
private fun Histogram(data: FloatArray?, modifier: Modifier) {
    data ?: return
    Canvas(modifier.size(width = 110.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).background(Glass)) {
        val bw = size.width / data.size
        data.forEachIndexed { i, v ->
            val h = v * size.height * 0.92f
            drawRect(Color.White.copy(alpha = 0.75f), Offset(i * bw, size.height - h), Size(bw + 0.5f, h))
        }
    }
}

// ---------------------------------------------------------------------------------- top bar

@Composable
private fun TopBar(e: CameraEngine, trayOpen: Boolean, rot: Float, onTray: () -> Unit, onSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp)) {
        if (e.isRecording) {
            val secs = e.recordNanos / 1_000_000_000L
            Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(6.dp)).background(RecRed)
                .padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text("%02d:%02d:%02d".format(secs / 3600, (secs / 60) % 60, secs % 60),
                    color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
            return@Box
        }
        val flashIcon: ImageVector = when (e.settings.flash) {
            0 -> Icons.Filled.FlashAuto; 1 -> Icons.Filled.FlashOn; else -> Icons.Filled.FlashOff
        }
        RoundIcon(flashIcon, if (e.settings.flash == 1) Yellow else Color.White, rot,
            Modifier.align(Alignment.CenterStart), enabled = e.hasFlash || e.lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT) { e.cycleFlash() }

        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (e.mode == Mode.PHOTO && e.darkScene) {
                RoundIcon(Icons.Filled.NightsStay, Color.White, rot) { e.changeMode(Mode.NIGHT) }
                Spacer(Modifier.width(6.dp))
            }
            RoundIcon(if (trayOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                if (trayOpen) Yellow else Color.White, 0f, onClick = onTray)
        }

        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            if (e.mode == Mode.VIDEO) {
                val res = if (e.settings.videoRes == 0) "4K" else "HD"
                Text("$res · ${e.settings.videoFps}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                        val s = e.settings
                        // Cycle like iPhone: 4K30 -> 4K60 -> HD30 -> HD60
                        when {
                            s.videoRes == 0 && s.videoFps == 30 -> s.videoFps = 60
                            s.videoRes == 0 -> { s.videoRes = 1; s.videoFps = 30 }
                            s.videoFps == 30 -> s.videoFps = 60
                            else -> { s.videoRes = 0; s.videoFps = 30 }
                        }
                        e.rebind()
                    }.padding(horizontal = 8.dp, vertical = 6.dp).rotate(rot))
                if (e.hdrVideoSupported) {
                    Text("HDR", color = if (e.settings.hdrVideo) Yellow else Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { e.settings.hdrVideo = !e.settings.hdrVideo; e.rebind() }
                            .padding(6.dp).rotate(rot))
                }
            }
            RoundIcon(Icons.Outlined.Settings, Color.White, rot, onClick = onSettings)
        }
    }
}

@Composable
fun RoundIcon(
    icon: ImageVector, tint: Color, rot: Float, modifier: Modifier = Modifier,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(
        modifier.size(36.dp).clip(CircleShape).background(Color(0x22FFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(20.dp).rotate(rot))
    }
}

// ---------------------------------------------------------------------------------- zoom

@Composable
private fun ZoomPills(e: CameraEngine, rot: Float) {
    val opts = e.lensOptions
    if (opts.isEmpty()) return
    val cur = e.displayZoom
    val active = opts.filter { it.displayZoom <= cur + 0.05f }.maxByOrNull { it.displayZoom } ?: opts.first()
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Glass).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        opts.forEach { o ->
            val sel = o == active
            val size by animateDpAsState(if (sel) 38.dp else 30.dp, label = "zp")
            val label = if (sel) fmtZoom(cur) + "×" else fmtZoom(o.displayZoom)
            Box(
                Modifier.size(size).clip(CircleShape).background(Color(0x55000000))
                    .clickable { e.selectLens(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Yellow else Color.White, fontSize = if (sel) 12.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.rotate(rot))
            }
        }
    }
}

fun fmtZoom(z: Float): String {
    val r = (z * 10).roundToInt() / 10f
    return when {
        r < 1f -> ".%d".format((r * 10).roundToInt())
        r == r.toInt().toFloat() -> r.toInt().toString()
        else -> "%.1f".format(r)
    }
}

// ---------------------------------------------------------------------------------- modes

@Composable
private fun ModeCarousel(e: CameraEngine) {
    val modes = Mode.entries
    val centers = remember { mutableStateMapOf<Int, Float>() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val center = constraints.maxWidth / 2f
        val target = centers[e.mode.ordinal]?.let { center - it } ?: 0f
        val offset by animateFloatAsState(target, tween(260), label = "carousel")
        Row(
            Modifier.wrapContentWidth(align = Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = offset },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            modes.forEachIndexed { i, m ->
                val sel = m == e.mode
                Text(
                    m.label,
                    color = if (sel) Yellow else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier
                        .onPlaced { c -> centers[i] = c.positionInParent().x + c.size.width / 2f }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { e.changeMode(m) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------- bottom bar

@Composable
private fun BottomBar(e: CameraEngine, rot: Float) {
    Row(
        Modifier.fillMaxWidth().height(112.dp).padding(horizontal = 30.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C1C1E))
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .clickable { e.openLast() },
            contentAlignment = Alignment.Center,
        ) {
            e.thumbnail?.let {
                Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().rotate(rot))
            }
            if (e.processing > 0) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                }
            }
        }

        ShutterButton(e)

        // Flip
        var flips by remember { mutableFloatStateOf(0f) }
        val flipRot by animateFloatAsState(flips, tween(400), label = "flip")
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(GlassLight)
                .clickable(enabled = !e.isRecording) { flips += 180f; e.flipCamera() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Cached, "Flip camera", tint = Color.White,
                modifier = Modifier.size(28.dp).rotate(flipRot + rot))
        }
    }
}

@Composable
private fun ShutterButton(e: CameraEngine) {
    var pressed by remember { mutableStateOf(false) }
    val video = e.mode.isVideo
    val rec = e.isRecording
    val innerSize by animateDpAsState(
        when { rec -> 30.dp; pressed -> 56.dp; else -> 64.dp }, tween(180), label = "inner")
    val corner by animateDpAsState(if (rec) 7.dp else 32.dp, tween(180), label = "corner")
    val innerColor = when {
        video -> RecRed
        e.capturing -> Color.White.copy(alpha = 0.55f)
        else -> Color.White
    }
    Box(
        Modifier.size(80.dp)
            .pointerInput(e.mode) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        e.stopBurst()
                    },
                    onTap = { e.onShutter() },
                    onLongPress = { if (!video) e.startBurst() else e.onShutter() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White, radius = size.minDimension / 2 - 2.5.dp.toPx(), style = Stroke(4.dp.toPx()))
            if (e.capturing && e.captureProgress > 0f) {
                drawArc(Yellow, -90f, 360f * e.captureProgress, false,
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(2.5.dp.toPx(), 2.5.dp.toPx()),
                    size = Size(size.width - 5.dp.toPx(), size.height - 5.dp.toPx()))
            }
        }
        Canvas(Modifier.size(innerSize)) {
            drawRoundRect(innerColor, cornerRadius = CornerRadius(corner.toPx(), corner.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------------- portrait

@Composable
private fun ApertureButton(e: CameraEngine, rot: Float, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("ƒ", color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.rotate(rot))
    }
}

private val APERTURES = listOf(1.4f, 1.8f, 2.0f, 2.2f, 2.8f, 3.5f, 4.0f, 4.5f, 5.6f, 6.3f, 8f, 11f, 16f)

@Composable
private fun ApertureSlider(e: CameraEngine) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("ƒ %.1f".format(e.aperture), color = Yellow, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Ruler(
            count = APERTURES.size,
            selected = APERTURES.indexOfFirst { it >= e.aperture - 0.01f }.coerceAtLeast(0),
            labelFor = { if (it % 4 == 0) "%.1f".format(APERTURES[it]) else null },
        ) { e.aperture = APERTURES[it] }
    }
}

// ---------------------------------------------------------------------------------- tray

enum class TrayItem { ASPECT, TIMER, EXPOSURE, STYLE }

@Composable
private fun OptionsTray(e: CameraEngine, item: TrayItem?, onItem: (TrayItem) -> Unit) {
    val s = e.settings
    if (item != null) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            when (item) {
                TrayItem.ASPECT -> Chips(listOf("4:3", "16:9", "1:1"), s.aspect) { s.aspect = it; e.rebind() }
                TrayItem.TIMER -> Chips(listOf("Off", "3s", "10s"), listOf(0, 3, 10).indexOf(s.timer).coerceAtLeast(0)) {
                    s.timer = listOf(0, 3, 10)[it]
                }
                TrayItem.STYLE -> Chips(PhotoStyle.entries.map { it.label }, s.style, scroll = true) { s.style = it }
                TrayItem.EXPOSURE -> {
                    val steps = ((e.evMax - e.evMin) * 3).roundToInt().coerceAtLeast(1)
                    Ruler(count = steps + 1,
                        selected = ((e.ev - e.evMin) * 3).roundToInt().coerceIn(0, steps),
                        labelFor = { i -> val v = e.evMin + i / 3f; if (i % 3 == 0) "%+.0f".format(v) else null },
                        onSelect = { e.applyEv(e.evMin + it / 3f) })
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TrayButton(listOf("4:3", "16:9", "1:1")[s.aspect], s.aspect != 0) { onItem(TrayItem.ASPECT) }
        TrayButton(if (s.timer == 0) "Timer" else "${s.timer}s", s.timer != 0) { onItem(TrayItem.TIMER) }
        TrayButton("±" + "%.1f".format(e.ev), e.ev != 0f) { onItem(TrayItem.EXPOSURE) }
        TrayButton("Style", s.style != 0) { onItem(TrayItem.STYLE) }
        TrayButton("Grid", s.grid) { s.grid = !s.grid }
    }
}

@Composable
private fun TrayButton(text: String, active: Boolean, onClick: () -> Unit) {
    Text(text, color = if (active) Yellow else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
fun Chips(items: List<String>, selected: Int, scroll: Boolean = false, onSelect: (Int) -> Unit) {
    val content: @Composable () -> Unit = {
        items.forEachIndexed { i, t ->
            val sel = i == selected
            Text(t, color = if (sel) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 3.dp).clip(RoundedCornerShape(50))
                    .background(if (sel) Yellow else Color(0x22FFFFFF)).clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    if (scroll) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { content() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}
