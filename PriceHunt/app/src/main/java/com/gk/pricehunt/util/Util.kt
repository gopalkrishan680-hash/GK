package com.gk.pricehunt.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.gk.pricehunt.data.Store
import java.text.NumberFormat
import java.util.Locale

private val inr: NumberFormat = NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }

fun rupees(v: Double): String = "₹" + inr.format(v)

/**
 * Opens a product directly inside the store's own app when it's installed
 * (Amazon, Flipkart, Myntra, Meesho…), otherwise in the browser.
 */
fun openInStore(ctx: Context, url: String, store: Store?) {
    val uri = Uri.parse(url)
    val pkg = store?.appPackage
    if (pkg != null) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) { /* app not installed -> fall through */ }
    }
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app found to open this link", Toast.LENGTH_SHORT).show()
    }
}

fun isAppInstalled(ctx: Context, pkg: String?): Boolean = pkg != null &&
    runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

fun shareText(ctx: Context, text: String) {
    ctx.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun timeAgo(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        d < 86400 -> "${d / 3600} h ago"
        else -> "${d / 86400} d ago"
    }
}

fun extractUrl(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')')
