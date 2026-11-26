package com.gk.papercut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gk.papercut.ui.EditorScreen
import com.gk.papercut.ui.HomeScreen
import com.gk.papercut.ui.SettingsScreen
import com.gk.papercut.ui.StudioViewModel

class MainActivity : ComponentActivity() {
    private val vm: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = PaperColors) {
                val project by vm.project.collectAsStateWithLifecycle()
                var settings by remember { mutableStateOf(false) }
                when {
                    settings -> { BackHandler { settings = false }; SettingsScreen(vm) { settings = false } }
                    project != null -> { BackHandler { vm.close() }; EditorScreen(vm, project!!) }
                    else -> HomeScreen(vm, onSettings = { settings = true })
                }
            }
        }
    }
}

val PaperColors = lightColorScheme(
    primary = Color(0xFFD9542F),
    onPrimary = Color.White,
    secondary = Color(0xFF2F7A68),
    tertiary = Color(0xFFE9A92B),
    background = Color(0xFFF6EEDC),
    surface = Color(0xFFFFFBF2),
    surfaceVariant = Color(0xFFF0E4CC),
    primaryContainer = Color(0xFFFFDCCF),
    secondaryContainer = Color(0xFFCDEBE2),
)
