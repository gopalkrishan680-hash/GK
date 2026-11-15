package com.kahani.studio3d

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kahani.studio3d.ui.HomeScreen
import com.kahani.studio3d.ui.K
import com.kahani.studio3d.ui.KahaniTheme
import com.kahani.studio3d.ui.ProjectScreen
import com.kahani.studio3d.ui.SettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KahaniTheme {
                val nav = rememberNavController()
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
                Box(Modifier.fillMaxSize().background(K.Bg)) {
                    NavHost(nav, startDestination = "home") {
                        composable("home") {
                            HomeScreen(vm,
                                onOpen = { id -> vm.open(id); nav.navigate("project/$id") },
                                onSettings = { nav.navigate("settings") })
                        }
                        composable("project/{id}") { e ->
                            val id = e.arguments?.getString("id") ?: return@composable
                            LaunchedEffect(id) { vm.open(id) }
                            ProjectScreen(vm, onBack = { vm.refresh(); nav.popBackStack() })
                        }
                        composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
                    }
                    SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
                }
            }
        }
    }
}
