package com.gk.procam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gk.procam.CameraEngine
import com.gk.procam.ModelState
import com.gk.procam.PhotoStyle

private val Card = Color(0xFF1C1C1E)
private val Secondary = Color(0xFF8E8E93)
private val Green = Color(0xFF34C759)
private val Separator = Color(0xFF38383A)

@Composable
fun SettingsSheet(e: CameraEngine, onClose: () -> Unit) {
    val s = e.settings
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp)) {
            Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center))
            Text("Done", color = Yellow, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterEnd).clickable(onClick = onClose).padding(8.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding()) {

            Section("PHOTO CAPTURE") {
                SwitchRow("Smart HDR", "Uses the phone's HDR engine when available, otherwise ProCam Fusion.", s.smartHdr) { s.smartHdr = it }
                Divider()
                SegmentRow("ProCam Fusion", listOf("Off", "Auto", "Always"), s.fusion,
                    "Merges up to 6 aligned frames for less noise and more detail. Auto kicks in when light drops.") { s.fusion = it }
                Divider()
                SwitchRow("50 MP High Resolution", "Full sensor resolution in Photo and Pro modes (single frame).", s.highRes) { s.highRes = it }
                Divider()
                SwitchRow(
                    "Ultra HDR Photos",
                    if (e.ultraHdrSupported) "Saves an HDR gain map. Highlights glow on HDR screens." else "Not supported on this camera.",
                    s.ultraHdr, enabled = e.ultraHdrSupported,
                ) { s.ultraHdr = it }
                Divider()
                SwitchRow("RAW + JPEG in Pro", if (e.rawSupported) "Also saves a DNG for editing in Lightroom / Snapseed." else "RAW isn't exposed by this camera.",
                    s.proRaw, enabled = e.rawSupported) { s.proRaw = it }
                Divider()
                SegmentRow("Night Engine", listOf("Auto", "ProCam Stack"), s.nightEngine,
                    if (e.nightDeviceAvailable) "Auto uses Motorola's Night Vision. ProCam Stack merges up to 12 frames."
                    else "ProCam Stack merges up to 12 aligned frames.") { s.nightEngine = it }
                Divider()
                SegmentRow("Portrait Engine", listOf("ProCam AI", "Device"), s.portraitEngine,
                    "ProCam AI lets you change the aperture from ƒ1.4 to ƒ16.") { s.portraitEngine = it }
            }

            Section("AI MODELS") {
                val st = e.portraitAi.state
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Portrait segmentation", color = Color.White, fontSize = 16.sp)
                        Text(when (st) {
                            ModelState.READY -> "Installed · on-device, works offline"
                            ModelState.DOWNLOADING -> "Downloading via Google Play services… ${(e.portraitAi.progress * 100).toInt()}%"
                            ModelState.CHECKING -> "Checking…"
                            ModelState.FAILED -> "Not installed. Needs internet and Google Play services."
                        }, color = Secondary, fontSize = 12.sp)
                        if (st == ModelState.DOWNLOADING) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { e.portraitAi.progress }, color = Yellow,
                                trackColor = Separator, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (st == ModelState.FAILED) {
                        Text("Download", color = Yellow, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { e.portraitAi.download() }.padding(8.dp))
                    }
                }
            }

            Section("VIDEO") {
                SegmentRow("Record Video", listOf("4K", "1080p"), s.videoRes) { s.videoRes = it }
                Divider()
                SegmentRow("Frame Rate", listOf("30 fps", "60 fps"), if (s.videoFps == 60) 1 else 0) { s.videoFps = if (it == 1) 60 else 30 }
                Divider()
                SwitchRow("HDR Video (10-bit HLG)", if (e.hdrVideoSupported) "Brighter highlights and richer colours." else "Not supported on this camera.",
                    s.hdrVideo, enabled = e.hdrVideoSupported) { s.hdrVideo = it }
                Divider()
                SwitchRow("Stabilization", "Smooths hand-held footage.", s.stabilization) { s.stabilization = it }
                Divider()
                SegmentRow("Slo-mo", listOf("120 fps", "240 fps"), if (s.slomoFps == 240) 1 else 0) { s.slomoFps = if (it == 1) 240 else 120 }
                Divider()
                SegmentRow("Time-lapse Speed", listOf("5×", "15×", "30×", "60×"),
                    listOf(5, 15, 30, 60).indexOf(s.timelapseSpeed).coerceAtLeast(0)) { s.timelapseSpeed = listOf(5, 15, 30, 60)[it] }
            }

            Section("COMPOSITION") {
                SwitchRow("Grid", null, s.grid) { s.grid = it }
                Divider()
                SwitchRow("Level", "Shows a horizon line when the phone is nearly straight.", s.level) { s.level = it }
                Divider()
                SwitchRow("Mirror Front Camera", null, s.mirrorFront) { s.mirrorFront = it }
                Divider()
                SegmentRow("Photographic Style", PhotoStyle.entries.map { it.label }, s.style, scroll = true) { s.style = it }
            }

            Section("GENERAL") {
                SwitchRow("Scan QR Codes", null, s.qrScan) { s.qrScan = it }
                Divider()
                SwitchRow("Volume Buttons Take Photo", null, s.volumeShutter) { s.volumeShutter = it }
                Divider()
                SwitchRow("Shutter Sound", null, s.shutterSound) { s.shutterSound = it }
                Divider()
                SwitchRow("Haptics", null, s.haptics) { s.haptics = it }
            }

            Text("ProCam 1.0 · Photos are saved to DCIM/ProCam", color = Secondary, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(22.dp))
    Text(title, color = Secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card), content = content)
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Separator)

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Color.White else Secondary, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Secondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked && enabled, enabled) { onChange(it) }
    }
}

@Composable
fun IosSwitch(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (checked) Green else Color(0xFF39393D), label = "sw")
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, label = "swx")
    Box(
        Modifier.size(width = 51.dp, height = 31.dp).clip(RoundedCornerShape(50)).background(bg)
            .clickable(enabled = enabled) { onChange(!checked) }.padding(2.dp),
    ) {
        Box(Modifier.offset(x = x).size(27.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun SegmentRow(title: String, options: List<String>, selected: Int, subtitle: String? = null,
                       scroll: Boolean = false, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        if (subtitle != null) Text(subtitle, color = Secondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (scroll) {
            Chips(options, selected, scroll = true, onSelect = onSelect)
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(Color(0xFF2C2C2E)).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEachIndexed { i, t ->
                    val sel = i == selected
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(7.dp))
                            .background(if (sel) Color(0xFF636366) else Color.Transparent)
                            .clickable { onSelect(i) }.padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t, color = Color.White, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
