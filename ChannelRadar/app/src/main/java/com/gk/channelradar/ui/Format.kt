package com.gk.channelradar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

fun fmtCount(n: Long): String {
    val a = abs(n.toDouble())
    val s = when {
        a >= 1e9 -> "%.1fB".format(n / 1e9)
        a >= 1e6 -> "%.1fM".format(n / 1e6)
        a >= 1e3 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }
    return s.replace(".0", "")
}

fun fmtCount(d: Double): String = fmtCount(d.toLong())

fun fmtX(x: Double): String = when {
    x >= 100 -> "${x.toInt()}x"
    x >= 10 -> "%.0fx".format(x)
    else -> "%.1fx".format(x)
}

fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
    val h = (now - ms) / 3_600_000.0
    return when {
        h < 1 -> "${(h * 60).toInt().coerceAtLeast(1)}m ago"
        h < 48 -> "${h.toInt()}h ago"
        h < 24 * 60 -> "${(h / 24).toInt()}d ago"
        h < 24 * 365 -> "${(h / 24 / 30).toInt()}mo ago"
        else -> "%.1fy ago".format(h / 24 / 365)
    }
}

fun fmtDate(ms: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))

fun fmtDuration(sec: Int): String = when {
    sec <= 0 -> ""
    sec >= 3600 -> "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else -> "%d:%02d".format(sec / 60, sec % 60)
}

fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
    }
}

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
