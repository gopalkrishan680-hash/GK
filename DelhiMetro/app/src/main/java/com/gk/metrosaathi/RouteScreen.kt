package com.gk.metrosaathi

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun RouteScreen(state: AppState) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf<String?>(null) } // "from" / "to"
    var recents by remember { mutableStateOf(state.prefs.recents()) }

    LaunchedEffect(Unit) { state.refreshAlarm() }

    val from = state.from
    val to = state.to
    val options = remember(from, to) {
        if (from != null && to != null) state.planner.options(from, to) else emptyList()
    }
    LaunchedEffect(from, to) {
        if (from != null && to != null && options.isNotEmpty()) {
            state.prefs.addRecent(from, to)
            recents = state.prefs.recents()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Metro Saathi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Delhi NCR Metro – route, fare, map (offline)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        // ---------------------------------------------------------------- from / to
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PickField("Kahan se", from) { picking = "from" }
                    Spacer(Modifier.height(8.dp))
                    PickField("Kahan tak", to) { picking = "to" }
                }
                IconButton(onClick = {
                    val f = state.from
                    state.from = state.to
                    state.to = f
                }) { Icon(Icons.Filled.SwapVert, "Swap") }
            }
        }

        // ---------------------------------------------------------------- alarm banner
        val alarmStation = state.alarmStation
        if (alarmStation != null && state.alarmAt > System.currentTimeMillis()) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Alarm, null, tint = Color(0xFF8A6D00))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Utarne ka alarm: $alarmStation", fontWeight = FontWeight.SemiBold, color = Color(0xFF5C4600))
                        Text("Bajega ${timeText(state.alarmAt)} par", fontSize = 12.sp, color = Color(0xFF5C4600))
                    }
                    IconButton(onClick = {
                        TripAlarm.cancel(context)
                        state.refreshAlarm()
                    }) { Icon(Icons.Filled.AlarmOff, "Cancel alarm", tint = Color(0xFF8A6D00)) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (from != null && to != null) {
            if (options.isEmpty()) {
                Text("Is route ka rasta nahi mila.", color = MaterialTheme.colorScheme.error)
            } else {
                var selected by remember(from, to) { mutableIntStateOf(0) }
                if (options.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected == 0, onClick = { selected = 0 }, label = { Text("Sabse tez") })
                        FilterChip(selected == 1, onClick = { selected = 1 }, label = { Text("Kam line change") })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                JourneyCard(state, options[selected.coerceAtMost(options.lastIndex)])
            }
        } else {
            // ---------------------------------------------------------------- home content
            if (recents.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Recent routes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.prefs.clearRecents(); recents = emptyList() }) { Text("Clear") }
                }
                recents.forEach { (a, b) ->
                    Text(
                        "$a  →  $b",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { state.from = a; state.to = b }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            TipsCard()
        }
    }

    picking?.let { which ->
        StationPicker(
            state = state,
            title = if (which == "from") "Kahan se?" else "Kahan tak?",
            onPick = { name ->
                if (which == "from") state.setFrom(name) else state.setTo(name)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun PickField(label: String, value: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value ?: "Station chuniye",
            fontSize = 16.sp,
            fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (value != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun JourneyCard(state: AppState, j: Journey) {
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Summary
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Stat("${j.minutes} min", "Samay (approx)")
                Stat("${j.stationCount}", "Stations")
                Stat("${j.interchanges}", "Line change")
                Stat(formatKm(j.km), "Doori")
            }
            Spacer(Modifier.height(12.dp))

            // Fare
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                    .padding(12.dp),
            ) {
                if (j.dmrcKm > 0.05) {
                    val sunday = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                    Text("Kiraya (approx)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        FareItem("Token", Fare.token(j.dmrcKm), highlight = !sunday)
                        FareItem("Smart card", Fare.smartCard(j.dmrcKm), highlight = false)
                        FareItem("Sunday/holiday", Fare.sundayHoliday(j.dmrcKm), highlight = sunday)
                    }
                }
                j.separateFareLines.forEach {
                    Text("+ ${it.shortName}: ${it.note.ifBlank { "alag kiraya" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))

            // Timeline
            j.legs.forEachIndexed { i, leg ->
                when (leg) {
                    is Leg.Ride -> {
                        if (i > 0 && j.legs[i - 1] is Leg.Ride) {
                            ChangeRow("${leg.stations.first()} par ${leg.line.shortName} ke liye line badlein (~5 min)")
                        }
                        RideBlock(state, leg)
                    }
                    is Leg.Walk -> ChangeRow("${leg.from} se ${leg.to} tak paidal (~${leg.minutes} min)")
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        val lead = 3
                        val mins = (j.minutes - lead).coerceAtLeast(1)
                        val at = System.currentTimeMillis() + mins * 60_000L
                        TripAlarm.schedule(context, j.to, at)
                        state.refreshAlarm()
                        Toast.makeText(context, "Alarm ${timeText(at)} par bajega (≈$mins min)", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Alarm, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Utarne ka alarm")
                }
                OutlinedButton(onClick = { state.from = null; state.to = null }) { Text("Naya route") }
            }
            Text(
                "Alarm abhi metro me baithne ke hisaab se lagta hai, aapke station se ~3 min pehle bajega.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FareItem(label: String, value: Int, highlight: Boolean) {
    Column {
        Text("₹$value", fontWeight = FontWeight.Bold, fontSize = if (highlight) 20.sp else 16.sp, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChangeRow(text: String) {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RideBlock(state: AppState, leg: Leg.Ride) {
    var expanded by remember { mutableStateOf(false) }
    val color = leg.line.composeColor()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(24.dp).fillMaxHeight().padding(vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Train, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(leg.stations.first(), fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                LineChip(leg.line)
                Spacer(Modifier.width(6.dp))
                Text("towards ${leg.towards}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val mid = leg.stations.drop(1).dropLast(1)
            Text(
                "Agla station: ${leg.stations[1]}  •  ${leg.stations.size - 1} stop  •  ~${Math.round(leg.minutes)} min",
                fontSize = 12.sp,
            )
            if (mid.isNotEmpty()) {
                Text(
                    if (expanded) "▲ Stations chhupaiye" else "▼ ${mid.size} beech ke stations dekhiye",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp),
                )
                if (expanded) mid.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Spacer(Modifier.width(8.dp))
                        Text(s, fontSize = 13.sp, modifier = Modifier.clickable { state.sheetStation = s })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Utrein: ${leg.stations.last()}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun TipsCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Kaise use karein", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            listOf(
                "📍 'Kahan se' me GPS se apna sabse paas ka station chuniye",
                "🔁 Sabse tez ya kam line change wala rasta chuniye",
                "⏰ Metro me baithte hi 'Utarne ka alarm' lagaiye – so bhi jaayein to station miss nahi hoga",
                "📶 Ye app poori tarah offline chalti hai – tunnel me bhi",
                "🗺️ Map tab me zoom karke kisi bhi station par tap kariye",
            ).forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

fun timeText(millis: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
