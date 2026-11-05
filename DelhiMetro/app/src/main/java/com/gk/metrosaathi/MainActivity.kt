package com.gk.metrosaathi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val Brand = Color(0xFF0B3D91)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8FB4FF), secondary = Color(0xFFFFCC00))
                else lightColorScheme(primary = Brand, secondary = Color(0xFFB8860B)),
            ) {
                App()
            }
        }
    }
}

/** Shared app state passed to all screens. */
class AppState(
    val data: MetroData,
    val planner: RoutePlanner,
    val prefs: Prefs,
) {
    var tab by mutableIntStateOf(0)
    var from by mutableStateOf<String?>(null)
    var to by mutableStateOf<String?>(null)
    var sheetStation by mutableStateOf<String?>(null)
    var favorites by mutableStateOf(prefs.favorites())
    var alarmStation by mutableStateOf(prefs.alarmStation)
    var alarmAt by mutableStateOf(prefs.alarmAt)

    fun refreshAlarm() {
        alarmStation = prefs.alarmStation
        alarmAt = prefs.alarmAt
        if (alarmAt in 1 until System.currentTimeMillis()) {
            alarmStation = null
            alarmAt = 0L
        }
    }

    fun toggleFavorite(name: String) {
        favorites = prefs.toggleFavorite(name)
    }

    fun setFrom(name: String) {
        from = name
        if (to == name) to = null
        tab = 0
    }

    fun setTo(name: String) {
        to = name
        if (from == name) from = null
        tab = 0
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    val state = remember {
        val data = MetroData.get(context.applicationContext)
        AppState(data, RoutePlanner(data), Prefs(context.applicationContext))
    }
    val tabs = listOf(
        Triple("Route", Icons.Filled.AltRoute, 0),
        Triple("Map", Icons.Filled.Map, 1),
        Triple("Lines", Icons.Filled.List, 2),
        Triple("Info", Icons.Filled.Info, 3),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { (label, icon, i) ->
                    NavigationBarItem(
                        selected = state.tab == i,
                        onClick = { state.tab = i },
                        icon = { Icon(icon, label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (state.tab) {
                0 -> RouteScreen(state)
                1 -> MapScreen(state)
                2 -> LinesScreen(state)
                else -> InfoScreen()
            }
        }
    }

    state.sheetStation?.let { name ->
        StationSheet(
            state = state,
            name = name,
            onDismiss = { state.sheetStation = null },
            onOpenMaps = { st ->
                val uri = Uri.parse("geo:${st.lat},${st.lon}?q=${st.lat},${st.lon}(${Uri.encode(st.name + " Metro Station")})")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (e: Exception) {
                    // No maps app installed.
                }
            },
        )
    }
}
