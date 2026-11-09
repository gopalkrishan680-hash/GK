package com.gk.speakup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gk.speakup.ui.screens.ChatScreen
import com.gk.speakup.ui.screens.HomeScreen
import com.gk.speakup.ui.screens.LevelResultScreen
import com.gk.speakup.ui.screens.LevelTestScreen
import com.gk.speakup.ui.screens.MistakesScreen
import com.gk.speakup.ui.screens.OnboardingScreen
import com.gk.speakup.ui.screens.ProgressScreen
import com.gk.speakup.ui.screens.PronunciationScreen
import com.gk.speakup.ui.screens.ScenariosScreen
import com.gk.speakup.ui.screens.SettingsScreen
import com.gk.speakup.ui.screens.VocabScreen
import com.gk.speakup.ui.theme.SpeakUpTheme

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph = AppGraph(this)

        setContent {
            SpeakUpTheme {
                CompositionLocalProvider(LocalGraph provides graph) {
                    val nav = rememberNavController()
                    val p = graph.store.profile
                    val start = when {
                        p.name.isBlank() || p.activeKey.isBlank() -> "onboarding"
                        p.level.isBlank() -> "levelTest"
                        else -> "home"
                    }
                    NavHost(navController = nav, startDestination = start) {
                        composable("onboarding") {
                            OnboardingScreen(onDone = {
                                nav.navigate("levelTest") { popUpTo("onboarding") { inclusive = true } }
                            })
                        }
                        composable("levelTest") {
                            LevelTestScreen(
                                onFinished = {
                                    nav.navigate("levelResult") { popUpTo("levelTest") { inclusive = true } }
                                },
                                onSkip = {
                                    nav.navigate("home") { popUpTo(0) }
                                },
                            )
                        }
                        composable("levelResult") {
                            LevelResultScreen(onContinue = {
                                nav.navigate("home") { popUpTo(0) }
                            })
                        }
                        composable("home") { HomeScreen(nav) }
                        composable("scenarios") { ScenariosScreen(nav) }
                        composable(
                            "chat/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.StringType }),
                        ) { entry ->
                            ChatScreen(entry.arguments?.getString("id") ?: "free", onBack = { nav.popBackStack() })
                        }
                        composable("pronunciation") { PronunciationScreen(onBack = { nav.popBackStack() }) }
                        composable("vocab") { VocabScreen(onBack = { nav.popBackStack() }) }
                        composable("mistakes") { MistakesScreen(nav) }
                        composable("progress") { ProgressScreen(nav) }
                        composable("settings") { SettingsScreen(nav) }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        graph.speech.stopSpeaking()
        graph.speech.cancelListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        graph.speech.release()
    }
}
