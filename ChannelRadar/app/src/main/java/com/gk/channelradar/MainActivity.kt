package com.gk.channelradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gk.channelradar.ui.AppViewModel
import com.gk.channelradar.ui.ChannelScreen
import com.gk.channelradar.ui.DiscoverScreen
import com.gk.channelradar.ui.RadarTheme
import com.gk.channelradar.ui.ResultsScreen
import com.gk.channelradar.ui.SettingsScreen
import com.gk.channelradar.ui.Tab
import com.gk.channelradar.ui.TrendsScreen
import com.gk.channelradar.ui.WatchlistScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RadarTheme { App() } }
    }
}

@Composable
fun App(vm: AppViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.message = null
        }
    }
    val detail = vm.detail
    BackHandler(enabled = detail != null) { vm.closeDetail() }
    BackHandler(enabled = detail == null && vm.tab != Tab.DISCOVER) { vm.tab = Tab.DISCOVER }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = vm.tab == Tab.DISCOVER && detail == null,
                    onClick = { vm.closeDetail(); vm.tab = Tab.DISCOVER },
                    icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Discover") }
                )
                NavigationBarItem(
                    selected = vm.tab == Tab.TRENDS && detail == null,
                    onClick = { vm.closeDetail(); vm.tab = Tab.TRENDS },
                    icon = { Icon(Icons.Filled.Star, null) }, label = { Text("Trends") }
                )
                NavigationBarItem(
                    selected = vm.tab == Tab.RESULTS && detail == null,
                    onClick = { vm.closeDetail(); vm.tab = Tab.RESULTS },
                    icon = {
                        val n = vm.result?.channels?.size ?: 0
                        BadgedBox(badge = { if (n > 0) Badge { Text(if (n > 99) "99+" else "$n") } }) {
                            Icon(Icons.AutoMirrored.Filled.List, null)
                        }
                    },
                    label = { Text("Results") }
                )
                NavigationBarItem(
                    selected = vm.tab == Tab.WATCHLIST && detail == null,
                    onClick = { vm.closeDetail(); vm.tab = Tab.WATCHLIST },
                    icon = { Icon(Icons.Filled.Favorite, null) }, label = { Text("Saved") }
                )
                NavigationBarItem(
                    selected = vm.tab == Tab.SETTINGS && detail == null,
                    onClick = { vm.closeDetail(); vm.tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (detail != null) ChannelScreen(vm, detail)
            else when (vm.tab) {
                Tab.DISCOVER -> DiscoverScreen(vm)
                Tab.TRENDS -> TrendsScreen(vm)
                Tab.RESULTS -> ResultsScreen(vm)
                Tab.WATCHLIST -> WatchlistScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
