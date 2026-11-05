package com.gk.metrosaathi

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale

fun MetroLine.composeColor() = Color(color)

@Composable
fun LineDot(line: MetroLine, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(line.composeColor()))
}

@Composable
fun LineChip(line: MetroLine, onClick: (() -> Unit)? = null) {
    val c = line.composeColor()
    val textColor = if (line.id == "YELLOW") Color.Black else Color.White
    Text(
        text = (if (line.upcoming) "Coming: " else "") + line.shortName,
        color = if (line.upcoming) c else textColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (line.upcoming) c.copy(alpha = 0.15f) else c)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

fun formatKm(km: Double) = String.format(Locale.US, "%.1f km", km)

/** Asks for location permission when needed, then returns nearest stations via [onResult]. */
@Composable
fun rememberNearestFinder(
    data: MetroData,
    onResult: (List<Pair<Station, Double>>?) -> Unit,
): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }

    fun locate() {
        busy = true
        scope.launch {
            val loc = LocationHelper.current(context)
            busy = false
            onResult(loc?.let { data.nearest(it.latitude, it.longitude) })
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locate() else onResult(null)
    }
    val trigger = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) locate()
        else launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    return busy to trigger
}

/** Full-screen searchable station chooser. */
@Composable
fun StationPicker(
    state: AppState,
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var nearest by remember { mutableStateOf<List<Pair<Station, Double>>?>(null) }
    var locError by remember { mutableStateOf(false) }
    val (busy, findNearest) = rememberNearestFinder(state.data) { res ->
        nearest = res
        locError = res == null
    }
    val results = remember(query) { state.data.search(query) }
    val favs = state.favorites.mapNotNull { state.data.stations[it] }.filter { it.lines.isNotEmpty() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Station ka naam likhiye…") },
                    singleLine = true,
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    if (query.isEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !busy) { findNearest() }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (busy) "Location dhoondh rahe hain…" else "Mere paas ka station (GPS)",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (locError) item {
                            Text(
                                "Location nahi mili. GPS on karke dobara try karein.",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        nearest?.let { list ->
                            items(list, key = { "n_" + it.first.name }) { (st, d) ->
                                StationRow(state, st, subtitle = "${formatKm(d)} door") { onPick(st.name) }
                            }
                            item { HorizontalDivider() }
                        }
                        if (favs.isNotEmpty()) {
                            item { SectionTitle("★ Favourites") }
                            items(favs, key = { "f_" + it.name }) { st -> StationRow(state, st) { onPick(st.name) } }
                            item { HorizontalDivider() }
                        }
                        item { SectionTitle("Saare stations (${results.size})") }
                    }
                    items(results, key = { it.name }) { st -> StationRow(state, st) { onPick(st.name) } }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun StationRow(state: AppState, st: Station, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.width(48.dp)) {
            st.lines.take(3).forEach { state.data.lineById[it]?.let { l -> LineDot(l, 10.dp) } }
        }
        Column(Modifier.weight(1f)) {
            Text(st.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = subtitle ?: st.lines.mapNotNull { state.data.lineById[it]?.shortName }.distinct().joinToString(" • ")
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StationSheet(state: AppState, name: String, onDismiss: () -> Unit, onOpenMaps: (Station) -> Unit) {
    val st = state.data.stations[name] ?: return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(st.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { state.toggleFavorite(st.name) }) {
                    val fav = st.name in state.favorites
                    Icon(
                        if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "Favourite",
                        tint = if (fav) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val info = listOfNotNull(
                st.layout.takeIf { it.isNotBlank() },
                if (st.isInterchange) "Interchange" else null,
                if (st.lines.isEmpty()) "Under construction" else null,
            ).joinToString(" • ")
            if (info.isNotEmpty()) Text(info, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (st.lines + st.upcomingLines).mapNotNull { state.data.lineById[it] }.forEach { LineChip(it) }
            }
            Spacer(Modifier.height(12.dp))
            // Neighbours on each line.
            st.lines.mapNotNull { state.data.lineById[it] }.forEach { line ->
                val i = line.stations.indexOf(st.name)
                val prev = line.stations.getOrNull(i - 1)
                val next = line.stations.getOrNull(i + 1)
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineDot(line, 10.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOfNotNull(prev?.let { "← $it" }, next?.let { "$it →" }).joinToString("   |   "),
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            if (st.lines.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.chooseFrom(st.name); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Yahan se") }
                    Button(onClick = { state.chooseTo(st.name); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Yahan tak") }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = { onOpenMaps(st) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Directions, null)
                Spacer(Modifier.width(8.dp))
                Text("Station tak ka rasta (Maps)")
            }
        }
    }
}
