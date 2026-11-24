package com.gk.facelessstudio

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gk.facelessstudio.ui.AppViewModel
import com.gk.facelessstudio.ui.CreateScreen
import com.gk.facelessstudio.ui.EditorScreen
import com.gk.facelessstudio.ui.HomeScreen
import com.gk.facelessstudio.ui.StudioTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioTheme {
                val nav = rememberNavController()
                val toast by vm.toast.collectAsState()
                LaunchedEffect(toast) {
                    toast?.let { Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show(); vm.consumeToast() }
                }
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(vm,
                            onNew = { vm.newProject(); nav.navigate("create/0") },
                            onOpen = { id -> vm.open(id); nav.navigate(if (vm.project.value.scenes.isEmpty()) "create/0" else "editor") })
                    }
                    composable("create/{step}") { e ->
                        CreateScreen(vm, startStep = e.arguments?.getString("step")?.toIntOrNull() ?: 0,
                            onBack = { vm.refresh(); nav.popBackStack() },
                            onGenerated = { nav.navigate("editor") { popUpTo("home") } })
                    }
                    composable("editor") {
                        EditorScreen(vm,
                            onBack = { vm.refresh(); nav.popBackStack("home", false) },
                            onEditSetup = { step -> nav.navigate("create/$step") })
                    }
                }
            }
        }
    }
}
