package com.gk.dotlauncher.util

import android.content.Context
import android.graphics.Color

/** Saari settings ek jagah. SharedPreferences memory me cache rehti hain, isliye baar-baar padhna sasta hai. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("dot_prefs", Context.MODE_PRIVATE)
    private val counts = context.applicationContext.getSharedPreferences("dot_launch_counts", Context.MODE_PRIVATE)

    var monoIcons: Boolean
        get() = sp.getBoolean("mono_icons", true)
        set(v) = sp.edit().putBoolean("mono_icons", v).apply()

    var dotWallpaper: Boolean
        get() = sp.getBoolean("dot_wallpaper", true)
        set(v) = sp.edit().putBoolean("dot_wallpaper", v).apply()

    var use24h: Boolean
        get() = sp.getBoolean("use_24h", false)
        set(v) = sp.edit().putBoolean("use_24h", v).apply()

    var showSeconds: Boolean
        get() = sp.getBoolean("show_seconds", true)
        set(v) = sp.edit().putBoolean("show_seconds", v).apply()

    var showLabels: Boolean
        get() = sp.getBoolean("show_labels", true)
        set(v) = sp.edit().putBoolean("show_labels", v).apply()

    var autoKeyboard: Boolean
        get() = sp.getBoolean("auto_keyboard", false)
        set(v) = sp.edit().putBoolean("auto_keyboard", v).apply()

    var doubleTapLock: Boolean
        get() = sp.getBoolean("double_tap_lock", true)
        set(v) = sp.edit().putBoolean("double_tap_lock", v).apply()

    var swipeDownNotifications: Boolean
        get() = sp.getBoolean("swipe_down_notif", true)
        set(v) = sp.edit().putBoolean("swipe_down_notif", v).apply()

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var drawerColumns: Int
        get() = sp.getInt("drawer_columns", 4)
        set(v) = sp.edit().putInt("drawer_columns", v).apply()

    var accentIndex: Int
        get() = sp.getInt("accent_index", 0)
        set(v) = sp.edit().putInt("accent_index", v).apply()

    val accent: Int get() = ACCENTS[accentIndex.coerceIn(0, ACCENTS.lastIndex)].second

    var note: String
        get() = sp.getString("note", "") ?: ""
        set(v) = sp.edit().putString("note", v).apply()

    /** Dock ke apps (keys), order ke saath. null = abhi set nahi hua. */
    var dock: List<String>?
        get() = sp.getString("dock", null)?.let { s -> if (s.isEmpty()) emptyList() else s.split("|") }
        set(v) = sp.edit().putString("dock", v?.joinToString("|")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    var widgetIds: List<Int>
        get() = sp.getString("widget_ids", "")!!.split(",").mapNotNull { it.toIntOrNull() }
        set(v) = sp.edit().putString("widget_ids", v.joinToString(",")).apply()

    fun isCardEnabled(key: String) = sp.getBoolean("card_$key", true)
    fun setCardEnabled(key: String, on: Boolean) = sp.edit().putBoolean("card_$key", on).apply()

    fun launchCount(key: String) = counts.getInt(key, 0)
    fun recordLaunch(key: String) = counts.edit().putInt(key, launchCount(key) + 1).apply()

    companion object {
        val ACCENTS = listOf(
            "Red" to Color.parseColor("#D71921"),
            "White" to Color.parseColor("#FFFFFF"),
            "Yellow" to Color.parseColor("#FFC83D"),
            "Green" to Color.parseColor("#3DDC84"),
            "Blue" to Color.parseColor("#4D8DFF"),
        )

        /** Home screen ke built-in widgets: key to naam */
        val CARDS = listOf(
            "clock" to "Dot clock",
            "battery" to "Battery",
            "calendar" to "Calendar",
            "torch" to "Torch",
            "storage" to "Storage & RAM",
            "analog" to "Analog clock",
            "note" to "Quick note",
        )
    }
}
