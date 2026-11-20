package com.gk.dotlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gk.dotlauncher.util.Fonts
import com.gk.dotlauncher.util.Palette
import com.gk.dotlauncher.util.Prefs
import com.gk.dotlauncher.util.dp

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            fitsSystemWindows = true
            addView(list)
        })
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "SETTINGS"
            typeface = Fonts.doto(context)
            textSize = 38f
            setTextColor(Palette.TEXT)
        })
        list.addView(TextView(this).apply {
            text = "Dot Launcher v1.0"
            typeface = Fonts.mono(context); textSize = 11f; setTextColor(Palette.DIM)
        })

        section("General")
        action("Set as default launcher", "Home button par yahi launcher khule") {
            open(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        action("Wallpaper", "System wallpaper badlein") {
            open(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
        }
        toggle("Dot grid wallpaper", "Kaala background + dots. Off = system wallpaper", prefs.dotWallpaper) { prefs.dotWallpaper = it }
        action("Accent color", Prefs.ACCENTS[prefs.accentIndex].first) {
            val names = Prefs.ACCENTS.map { it.first }.toTypedArray()
            dialog().setTitle("Accent color").setSingleChoiceItems(names, prefs.accentIndex) { d, w ->
                prefs.accentIndex = w; d.dismiss(); build()
            }.show()
        }
        toggle("Haptic feedback", null, prefs.haptics) { prefs.haptics = it }

        section("Clock")
        toggle("24-hour time", null, prefs.use24h) { prefs.use24h = it }
        toggle("Show seconds", "Clock me seconds ki dot line", prefs.showSeconds) { prefs.showSeconds = it }

        section("Apps & icons")
        toggle("Monochrome icons", "Minimal safed/kaale icons", prefs.monoIcons) { prefs.monoIcons = it }
        toggle("Show app names", null, prefs.showLabels) { prefs.showLabels = it }
        action("Drawer columns", "${prefs.drawerColumns}") {
            prefs.drawerColumns = if (prefs.drawerColumns >= 6) 3 else prefs.drawerColumns + 1
            build()
        }
        toggle("Keyboard auto-open", "Drawer kholte hi search", prefs.autoKeyboard) { prefs.autoKeyboard = it }
        action("Hidden apps", "${prefs.hidden.size} hidden") { hiddenApps() }

        section("Gestures")
        toggle("Double-tap to lock", "Accessibility service chahiye", prefs.doubleTapLock) { prefs.doubleTapLock = it }
        toggle("Swipe down = notifications", null, prefs.swipeDownNotifications) { prefs.swipeDownNotifications = it }
        action("Accessibility service", if (LockService.instance != null) "ON ●" else "OFF — tap to enable") {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        section("Home widgets")
        Prefs.CARDS.forEach { (key, name) ->
            toggle(name, null, prefs.isCardEnabled(key)) { prefs.setCardEnabled(key, it) }
        }
        list.addView(TextView(this).apply {
            text = "Tip: Home par khaali jagah long-press karke Android widgets bhi add kar sakte hain. " +
                "Dot Clock / Dot Date widgets dusre launchers me bhi milenge."
            typeface = Fonts.mono(context); textSize = 11f; setTextColor(Palette.DIM)
            setPadding(0, dp(16), 0, 0)
        })
    }

    private fun section(title: String) {
        list.addView(TextView(this).apply {
            text = title.uppercase()
            typeface = Fonts.mono(context)
            textSize = 11f
            letterSpacing = 0.15f
            setTextColor(prefs.accent)
            setPadding(dp(4), dp(28), 0, dp(8))
        })
    }

    private fun row(title: String, sub: String?): Pair<LinearLayout, LinearLayout> {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title; typeface = Fonts.mono(context); textSize = 14f; setTextColor(Palette.TEXT)
            })
            if (sub != null) addView(TextView(context).apply {
                text = sub; typeface = Fonts.mono(context); textSize = 11f; setTextColor(Palette.DIM)
            })
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(20), dp(14), dp(16), dp(14))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        }
        list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        return r to texts
    }

    private fun toggle(title: String, sub: String?, value: Boolean, set: (Boolean) -> Unit) {
        val (r, _) = row(title, sub)
        val sw = Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, c -> set(c) }
        }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
    }

    private fun action(title: String, sub: String?, onClick: () -> Unit) {
        val (r, _) = row(title, sub)
        r.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(Palette.DIM) })
        r.setOnClickListener { onClick() }
    }

    private fun hiddenApps() {
        val hidden = prefs.hidden.toList()
        if (hidden.isEmpty()) {
            Toast.makeText(this, "Koi app hidden nahi hai. Drawer me app long-press → Hide app", Toast.LENGTH_LONG).show()
            return
        }
        val la = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        val um = getSystemService(USER_SERVICE) as UserManager
        val labels = mutableMapOf<String, String>()
        um.userProfiles.forEach { u ->
            val serial = um.getSerialNumberForUser(u)
            la.getActivityList(null, u).forEach { labels["${it.componentName.flattenToShortString()}#$serial"] = it.label.toString() }
        }
        val names = hidden.map { labels[it] ?: it.substringBefore('/') }.toTypedArray()
        val checked = BooleanArray(hidden.size) { true }
        dialog().setTitle("Hidden apps (untick = wapas dikhao)")
            .setMultiChoiceItems(names, checked) { _, w, c -> checked[w] = c }
            .setPositiveButton("Save") { _, _ ->
                prefs.hidden = hidden.filterIndexed { i, _ -> checked[i] }.toSet(); build()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun open(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            Toast.makeText(this, "Ye setting is phone par nahi mili", Toast.LENGTH_SHORT).show()
        }
    }

}
