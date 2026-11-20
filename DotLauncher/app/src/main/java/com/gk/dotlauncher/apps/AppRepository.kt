package com.gk.dotlauncher.apps

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import com.gk.dotlauncher.util.Palette
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class AppItem(val info: LauncherActivityInfo, val label: String, val key: String)

/** Installed apps (work profile samet) load karta hai aur icons cache karta hai. */
class AppRepository(context: Context) {
    private val ctx = context.applicationContext
    val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconCache = ConcurrentHashMap<String, Drawable>()
    private val iconPx = (ctx.resources.displayMetrics.density * 56).toInt()

    @Volatile var apps: List<AppItem> = emptyList(); private set

    fun load(mono: Boolean, done: (List<AppItem>) -> Unit) {
        exec.execute {
            val list = userManager.userProfiles.flatMap { user ->
                val serial = userManager.getSerialNumberForUser(user)
                launcherApps.getActivityList(null, user).map { info ->
                    val key = "${info.componentName.flattenToShortString()}#$serial"
                    AppItem(info, info.label?.toString()?.trim() ?: info.componentName.packageName, key)
                }
            }.filter { it.info.componentName.packageName != ctx.packageName }
                .sortedBy { it.label.lowercase() }
            list.forEach { icon(it, mono) } // pehle se bana lo taaki scroll smooth rahe
            apps = list
            main.post { done(list) }
        }
    }

    fun find(key: String) = apps.firstOrNull { it.key == key }

    fun clearIcons() = iconCache.clear()

    fun icon(item: AppItem, mono: Boolean): Drawable {
        val k = item.key + if (mono) "#m" else ""
        return iconCache.getOrPut(k) {
            val raw = runCatching { item.info.getBadgedIcon(0) }.getOrNull()
                ?: ctx.packageManager.defaultActivityIcon
            if (mono) monochrome(raw) else raw
        }
    }

    /**
     * Android 13+ ke themed (monochrome) icon ho to use karte hain, warna icon ko
     * grayscale karke dark circle me rakhte hain – Nothing-style monochrome look.
     */
    private fun monochrome(d: Drawable): Drawable {
        val s = iconPx
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val clip = Path().apply { addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW) }
        c.clipPath(clip)
        c.drawColor(Palette.CARD_ALT)

        val mono = if (Build.VERSION.SDK_INT >= 33 && d is AdaptiveIconDrawable) d.monochrome else null
        if (mono != null) {
            val m = mono.constantState?.newDrawable()?.mutate() ?: mono.mutate()
            m.setTint(Palette.TEXT)
            val ext = s / 4
            m.setBounds(-ext, -ext, s + ext, s + ext)
            m.draw(c)
        } else {
            val g = d.constantState?.newDrawable()?.mutate() ?: d.mutate()
            g.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
                setSaturation(0f)
                // thoda contrast badhao
                postConcat(ColorMatrix(floatArrayOf(
                    1.25f, 0f, 0f, 0f, -20f,
                    0f, 1.25f, 0f, 0f, -20f,
                    0f, 0f, 1.25f, 0f, -20f,
                    0f, 0f, 0f, 1f, 0f
                )))
            })
            if (g is AdaptiveIconDrawable) g.setBounds(0, 0, s, s)
            else { val inset = s / 6; g.setBounds(inset, inset, s - inset, s - inset) }
            g.draw(c)
        }
        return BitmapDrawable(ctx.resources, bmp).apply { paint.isFilterBitmap = true; paint.flags = paint.flags or Paint.ANTI_ALIAS_FLAG }
    }
}
