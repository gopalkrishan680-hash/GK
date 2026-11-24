package com.gk.pricehunt

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gk.pricehunt.ui.screens.HomeScreen
import com.gk.pricehunt.ui.screens.LinkScreen
import com.gk.pricehunt.ui.screens.ProductScreen
import com.gk.pricehunt.ui.screens.ResultsScreen
import com.gk.pricehunt.ui.screens.SettingsScreen
import com.gk.pricehunt.ui.screens.WatchlistScreen
import com.gk.pricehunt.ui.theme.PriceHuntTheme
import com.gk.pricehunt.util.extractUrl

class MainActivity : ComponentActivity() {

    /** Pending navigation coming from a share intent or a notification. */
    private val pending = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            PriceHuntTheme {
                val nav = rememberNavController()
                val target by pending
                LaunchedEffect(target) {
                    target?.let { nav.navigate(it); pending.value = null }
                }
                AppScaffold(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_PRODUCT_ID)?.let { pending.value = "product/${Uri.encode(it)}"; return }
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val url = extractUrl(text)
            pending.value = if (url != null) "link?u=${Uri.encode(url)}" else "search/${Uri.encode(text.take(80))}"
        }
    }

    companion object {
        const val EXTRA_PRODUCT_ID = "product_id"
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Search", Icons.Filled.Home),
    Tab("watchlist", "Tracker", Icons.Filled.Favorite),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
private fun AppScaffold(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = if (showBar) pad.calculateBottomPadding() else androidx.compose.ui.unit.Dp(0f))
            .padding(top = if (route == "home") pad.calculateTopPadding() else androidx.compose.ui.unit.Dp(0f))) {
            NavHost(nav, startDestination = "home") {
                val openProduct: (String) -> Unit = { id -> nav.navigate("product/${Uri.encode(id)}") }
                val search: (String) -> Unit = { q -> nav.navigate("search/${Uri.encode(q)}") }

                composable("home") {
                    HomeScreen(
                        onSearch = search,
                        onLink = { nav.navigate("link?u=${Uri.encode(it)}") },
                        onOpenProduct = openProduct,
                        onSeeWatchlist = { nav.navigate("watchlist") { launchSingleTop = true } },
                    )
                }
                composable("watchlist") { WatchlistScreen(onOpenProduct = openProduct) }
                composable("settings") { SettingsScreen() }
                composable("search/{q}", arguments = listOf(navArgument("q") { type = NavType.StringType })) {
                    ResultsScreen(
                        query = it.arguments?.getString("q").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onOpenProduct = openProduct,
                    )
                }
                composable("product/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ProductScreen(
                        id = it.arguments?.getString("id").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onCompare = search,
                    )
                }
                composable("link?u={u}", arguments = listOf(navArgument("u") { type = NavType.StringType; defaultValue = "" })) {
                    LinkScreen(
                        url = it.arguments?.getString("u").orEmpty(),
                        onBack = { nav.popBackStack() },
                        onResolved = { id ->
                            nav.navigate("product/${Uri.encode(id)}") { popUpTo("link?u={u}") { inclusive = true } }
                        },
                    )
                }
            }
        }
    }
}
