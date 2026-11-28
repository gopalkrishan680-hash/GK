package com.gk.procam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.procam.CameraEngine
import com.gk.procam.ProParam
import com.gk.procam.WB_MODES
import kotlin.math.roundToInt

/**
 * iOS-style dial: a strip of ticks you drag sideways; the centre tick is the value.
 */
@Composable
fun Ruler(count: Int, selected: Int, labelFor: (Int) -> String?, onSelect: (Int) -> Unit) {
    val spacing = 14.dp
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val sel by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val measurer = rememberTextMeasurer()
    Box(
        Modifier.fillMaxWidth().height(46.dp)
            .pointerInput(count) {
                var start = 0
                detectHorizontalDragGestures(
                    onDragStart = { start = sel; drag = 0f },
                    onDragEnd = { drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { ch, dx ->
                    ch.consume()
                    drag += dx
                    val idx = (start - (drag / spacingPx).roundToInt()).coerceIn(0, count - 1)
                    if (idx != sel) select(idx)
                }
            }
            .pointerInput(count) {
                detectTapGestures { p ->
                    val idx = (sel + ((p.x - size.width / 2f) / spacingPx).roundToInt()).coerceIn(0, count - 1)
                    select(idx)
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            for (i in 0 until count) {
                val x = cx + (i - selected) * spacingPx
                if (x < -spacingPx || x > size.width + spacingPx) continue
                val label = labelFor(i)
                val major = label != null
                val h = if (major) 16.dp.toPx() else 9.dp.toPx()
                val c = if (i == selected) Yellow else Color.White.copy(alpha = if (major) 0.9f else 0.5f)
                drawLine(c, Offset(x, size.height - h), Offset(x, size.height), if (i == selected) 2.dp.toPx() else 1.dp.toPx())
                if (label != null) {
                    val layout = measurer.measure(label, TextStyle(color = c, fontSize = 10.sp))
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 0f))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------- Pro

@Composable
fun ProPanel(e: CameraEngine) {
    var param by remember { mutableStateOf<ProParam?>(null) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x99000000))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ProParam.entries.forEach { p ->
                val (value, manual) = when (p) {
                    ProParam.ISO -> (e.proIso?.toString() ?: e.liveIso.takeIf { it > 0 }?.toString() ?: "A") to (e.proIso != null)
                    ProParam.SHUTTER -> fmtShutter(e.proShutter ?: e.liveExposureNs) to (e.proShutter != null)
                    ProParam.WB -> WB_MODES[e.proWb].first to (e.proWb != 0)
                    ProParam.FOCUS -> (e.proFocus?.let { if (it < 0.02f) "∞" else "%.0f%%".format(it * 100) } ?: "AF") to (e.proFocus != null)
                    ProParam.EV -> "%+.1f".format(e.ev) to (e.ev != 0f)
                }
                Column(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (param == p) Color(0x33FFD60A) else Color.Transparent)
                        .clickable { param = if (param == p) null else p }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(p.label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    Text(value, color = if (manual) Yellow else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val p = param ?: return@Column
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text("AUTO", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Yellow).clickable {
                    when (p) {
                        ProParam.ISO -> e.updateProIso(null)
                        ProParam.SHUTTER -> e.updateProShutter(null)
                        ProParam.WB -> e.updateProWb(0)
                        ProParam.FOCUS -> e.updateProFocus(null)
                        ProParam.EV -> e.applyEv(0f)
                    }
                }.padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                when (p) {
                    ProParam.ISO -> {
                        val vals = e.isoValues
                        if (vals.isNotEmpty()) {
                            val cur = e.proIso ?: e.liveIso
                            Ruler(vals.size, vals.indexOfFirst { it >= cur }.let { if (it < 0) vals.size - 1 else it },
                                { i -> if (i % 3 == 0) vals[i].toString() else null }) { e.updateProIso(vals[it]) }
                        }
                    }
                    ProParam.SHUTTER -> {
                        val vals = e.shutterValues
                        if (vals.isNotEmpty()) {
                            val cur = e.proShutter ?: e.liveExposureNs
                            Ruler(vals.size, vals.indexOfFirst { it >= cur }.let { if (it < 0) vals.size - 1 else it },
                                { i -> if (i % 2 == 0) fmtShutter(vals[i]) else null }) { e.updateProShutter(vals[it]) }
                        }
                    }
                    ProParam.WB -> Chips(WB_MODES.map { it.first }, e.proWb, scroll = true) { e.updateProWb(it) }
                    ProParam.FOCUS -> {
                        val steps = 40
                        val cur = ((e.proFocus ?: 0f) * steps).roundToInt()
                        Ruler(steps + 1, cur, { i -> when (i) { 0 -> "∞"; steps -> "Macro"; else -> null } }) {
                            e.updateProFocus(it / steps.toFloat())
                        }
                    }
                    ProParam.EV -> {
                        val steps = ((e.evMax - e.evMin) * 3).roundToInt().coerceAtLeast(1)
                        Ruler(steps + 1, ((e.ev - e.evMin) * 3).roundToInt().coerceIn(0, steps),
                            { i -> if (i % 3 == 0) "%+.0f".format(e.evMin + i / 3f) else null }) { e.applyEv(e.evMin + it / 3f) }
                    }
                }
            }
        }
    }
}

fun fmtShutter(ns: Long): String {
    if (ns <= 0) return "A"
    val s = ns / 1e9
    return if (s >= 0.5) "%.0f\"".format(s) else "1/${(1 / s).roundToInt()}"
}

// ------------------------------------------------------------------------------- permission

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.CameraAlt, null, tint = Color.White, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("ProCam needs camera access", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Allow Camera and Microphone to take photos and record videos with sound.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        Text("Allow Access", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Yellow).clickable(onClick = onRequest)
                .padding(horizontal = 28.dp, vertical = 12.dp))
    }
}

// ------------------------------------------------------------------------------- crash report

@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().background(Color.Black).padding(20.dp).padding(top = 30.dp)) {
        Text("ProCam crashed last time", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Take a screenshot of this page or copy the report and send it to the developer.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row {
            Text("Copy report", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Yellow).clickable {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ProCam crash", report))
                }.padding(horizontal = 16.dp, vertical = 10.dp))
            Spacer(Modifier.width(10.dp))
            Text("Continue", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2E)).clickable(onClick = onContinue)
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(report, color = Color(0xFFFF9F0A), fontSize = 10.sp, lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
    }
}
