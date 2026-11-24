package com.gk.pricehunt.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gk.pricehunt.overlay.ShoppingAssistService

/** Live "is on-screen compare enabled?" state, refreshed whenever the screen resumes. */
@Composable
fun rememberAssistEnabled(): Boolean {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(ShoppingAssistService.isEnabled(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = ShoppingAssistService.isEnabled(ctx) }
    return enabled
}

fun openAccessibilitySettings(ctx: Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun openAppInfo(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Prominent disclosure + step-by-step guide shown before sending the user to Accessibility settings. */
@Composable
fun AssistSetupDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("See prices right inside shopping apps") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "When you open a product in Amazon, Flipkart, Myntra, Meesho and other supported apps, a small PriceHunt " +
                        "bubble appears on the screen showing the price of the same item on the other platforms. Tap it to see all prices " +
                        "and jump to the cheapest one.",
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("Privacy", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "This uses Android's Accessibility permission to read the product name and price on screen. " +
                        "PriceHunt only reads the supported shopping apps, never other apps, and nothing is stored or sent anywhere " +
                        "except the normal product searches to the stores.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("How to turn it on", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    "1. Tap \"Open settings\" below.\n" +
                        "2. Open \"Installed apps\" / \"Downloaded apps\" → \"PriceHunt on-screen price compare\" and switch it on.\n\n" +
                        "If the switch is greyed out (\"Restricted setting\"): go to App info → ⋮ (top-right) → " +
                        "\"Allow restricted settings\", then try again. Android does this for apps installed from outside the Play Store.",
                    fontSize = 13.sp,
                )
                TextButton(onClick = { openAppInfo(ctx) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text("Open PriceHunt App info")
                }
            }
        },
        confirmButton = { Button(onClick = { onDismiss(); openAccessibilitySettings(ctx) }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Promo card on the home screen while the feature is off. */
@Composable
fun AssistPromoCard() {
    var dialog by remember { mutableStateOf(false) }
    Card(
        onClick = { dialog = true },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NEW · On-screen price compare", fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("See other platforms' prices right inside the Amazon, Flipkart, Myntra & Meesho apps.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Button(onClick = { dialog = true }) { Text("Turn on") }
        }
    }
    if (dialog) AssistSetupDialog { dialog = false }
}
