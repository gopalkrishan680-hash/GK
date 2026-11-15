package com.gk.viralradar

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gk.viralradar.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { ViralTheme { App(vm, sharedText) { sharedText = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) sharedText = i.getStringExtra(Intent.EXTRA_TEXT)
    }
}

private enum class Tab(val label: String) { TRENDING("Trending"), DISCOVER("Discover"), TRACKER("Tracker"), INSIGHTS("Insights"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: AppViewModel, shared: String?, consumeShared: () -> Unit) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(if (vm.prefs.apiKey.isBlank()) Tab.SETTINGS else Tab.TRENDING) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val selectedTrack by vm.selectedTrack.collectAsStateWithLifecycle()
    val msg by vm.watchMsg.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.watchMsg.value = null } }
    LaunchedEffect(shared) {
        shared?.let { vm.discover(it, 48, false); tab = Tab.DISCOVER; consumeShared() }
    }

    Scaffold(
        topBar = {
            if (selected == null && selectedTrack == null) TopAppBar(title = { Text("📡 Viral Radar", fontWeight = FontWeight.Black) })
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; vm.selected.value = null; vm.selectedTrack.value = null },
                        icon = {
                            Icon(when (t) {
                                Tab.TRENDING -> Icons.Default.Whatshot
                                Tab.DISCOVER -> Icons.Default.TravelExplore
                                Tab.TRACKER -> Icons.Default.Timeline
                                Tab.INSIGHTS -> Icons.Default.Insights
                                Tab.SETTINGS -> Icons.Default.Settings
                            }, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when {
                selected != null -> VideoDetailScreen(vm, selected!!)
                selectedTrack != null -> TrackDetailScreen(vm, selectedTrack!!)
                !hasKey && tab != Tab.SETTINGS -> NoKeyCard { tab = Tab.SETTINGS }
                else -> when (tab) {
                    Tab.TRENDING -> TrendingScreen(vm)
                    Tab.DISCOVER -> DiscoverScreen(vm)
                    Tab.TRACKER -> TrackerScreen(vm)
                    Tab.INSIGHTS -> InsightsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}
