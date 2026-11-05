package com.gk.metrosaathi

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LinesScreen(state: AppState) {
    var open by remember { mutableStateOf<String?>(null) }
    val line = open?.let { state.data.lineById[it] }
    if (line != null) {
        BackHandler { open = null }
        LineDetail(state, line) { open = null }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        item {
            Text(
                "Metro lines",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp),
            )
        }
        item { SectionTitle("Chalu lines (${state.data.operationalLines.size})") }
        items(state.data.operationalLines, key = { it.id }) { l -> LineRow(l) { open = l.id } }
        item { Spacer(Modifier.height(8.dp)) }
        item { SectionTitle("🚧 Aane wali lines / under construction") }
        items(state.data.upcomingLines, key = { it.id }) { l -> LineRow(l) { open = l.id } }
        item {
            Text(
                "Aane wali lines ki jaankari news reports par based hai – stations aur dates badal sakti hain.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun LineRow(l: MetroLine, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(8.dp).height(44.dp).clip(RoundedCornerShape(4.dp))
                .background(l.composeColor().copy(alpha = if (l.upcoming) 0.5f else 1f)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(l.name, fontWeight = FontWeight.SemiBold)
            Text("${l.first}  ↔  ${l.last}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val extra = listOfNotNull(
                "${l.stations.size} stations",
                l.km.lastOrNull()?.takeIf { it > 0 }?.let { "~${formatKm(it)}" },
                l.note.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
            Text(extra, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LineDetail(state: AppState, line: MetroLine, onBack: () -> Unit) {
    val color = line.composeColor()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(color.copy(alpha = if (line.upcoming) 0.6f else 1f)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = if (line.id == "YELLOW") Color.Black else Color.White
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = fg) }
            Column {
                Text(line.name, color = fg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${line.stations.size} stations • ${line.first} ↔ ${line.last}", color = fg, fontSize = 12.sp)
            }
        }
        if (line.note.isNotBlank()) {
            Text(line.note, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(line.stations) { i, name ->
                val st = state.data.stations.getValue(name)
                val others = (st.lines + st.upcomingLines).filter { it != line.id }.mapNotNull { state.data.lineById[it] }
                    .distinctBy { it.shortName + it.upcoming }
                Row(
                    Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        .clickable { state.sheetStation = name }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.width(24.dp).fillMaxHeight()) {
                        val cx = size.width / 2
                        val stroke = 5.dp.toPx()
                        val effect = if (line.upcoming) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
                        if (i > 0) drawLine(color, Offset(cx, 0f), Offset(cx, size.height / 2), stroke, pathEffect = effect)
                        if (i < line.stations.lastIndex) drawLine(color, Offset(cx, size.height / 2), Offset(cx, size.height), stroke, pathEffect = effect)
                        val r = if (others.isNotEmpty()) 7.dp.toPx() else 5.dp.toPx()
                        drawCircle(Color.White, r, Offset(cx, size.height / 2))
                        drawCircle(if (others.isNotEmpty()) Color.Black else color, r, Offset(cx, size.height / 2), style = Stroke(2.dp.toPx()))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(name, fontWeight = if (others.isNotEmpty()) FontWeight.Bold else FontWeight.Normal)
                        if (others.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 3.dp)) {
                                others.take(3).forEach { LineChip(it) }
                            }
                        }
                        if (line.upcoming && st.lines.isNotEmpty()) {
                            Text("Abhi bhi chalu station hai", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (!line.upcoming && line.km.isNotEmpty()) {
                        Text(formatKm(line.km[i]), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoScreen() {
    val context = LocalContext.current
    fun dial(n: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")))
        } catch (e: Exception) {
            // no dialer
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Jaankari", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Kiraya (DMRC, 25 Aug 2025 se)", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Doori", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("Aam din", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text("Sunday/holiday", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Fare.chart.forEach { (d, n, s) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(d, Modifier.weight(1f))
                        Text("₹$n", Modifier.weight(1f))
                        Text("₹$s", Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Smart card / NCMC card par ~10% chhoot. App ka kiraya andaaza hai – asli kiraya DMRC ki official doori par depend karta hai. " +
                        "Airport Express, Aqua Line (Noida) aur Rapid Metro (Gurugram) ka kiraya alag hai.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Madad / Helpline", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                HelpRow("DMRC helpline", "155370") { dial("155370") }
                HelpRow("Emergency (Police/Ambulance)", "112") { dial("112") }
                HelpRow("Women helpline", "181") { dial("181") }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Kaam ki baatein", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                listOf(
                    "🕔 Metro aam taur par subah ~5–6 baje se raat ~11 baje tak chalti hai. Har line/station ki pehli aur aakhri train alag hoti hai, station par check karein.",
                    "👩 Har train me chalne ki disha me pehla coach mahilaon ke liye reserved hota hai.",
                    "🎫 Token sirf usi din ke liye valid hai. Roz safar karte hain to smart card ya NCMC card sasta padta hai.",
                    "🎒 Bag security check ke liye taiyar rakhein. Jwalansheel cheezein le jaana mana hai.",
                    "🔁 Line badalte waqt 'Interchange' ke signboard follow karein.",
                    "📶 Ye app bina internet ke chalti hai, isliye tunnel me bhi route dekh sakte hain.",
                ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("App ke baare me", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Metro Saathi ek unofficial app hai, iska DMRC ya kisi metro company se koi sambandh nahi hai. " +
                        "Samay aur kiraya andaaze hain, isliye zaroori safar se pehle official jaankari zaroor check karein.",
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Station ka order aur location: © OpenStreetMap contributors (ODbL). " +
                        "Aane wali lines: PIB press release aur news reports.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright")))
                    } catch (e: Exception) {
                    }
                }) { Text("OpenStreetMap license") }
            }
        }
    }
}

@Composable
private fun HelpRow(label: String, number: String, onCall: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onCall() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Call, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(number, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
