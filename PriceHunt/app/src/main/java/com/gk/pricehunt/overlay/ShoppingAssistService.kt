package com.gk.pricehunt.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.gk.pricehunt.PriceHuntApp
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.scrape.Matcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the Amazon / Flipkart / Myntra / Meesho … apps. When a product page is open it reads
 * the product name + price from the screen and shows a bubble with that item's price on the
 * other platforms. Screen content of any other app is never read.
 */
class ShoppingAssistService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var overlay: CompareOverlay
    private var inspectJob: Job? = null
    private var compareJob: Job? = null

    private var currentTitle: String? = null
    private var candidate: String? = null
    private var candidateHits = 0
    private val dismissed = LinkedHashSet<String>()
    private var notProductStreak = 0

    private val app get() = application as PriceHuntApp

    override fun onServiceConnected() {
        overlay = CompareOverlay(this) { title -> dismissed += title; if (dismissed.size > 30) dismissed.remove(dismissed.first()) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        val store = Store.fromPackage(pkg)
        if (store == null) {
            // user switched to some other app -> remove the bubble; nothing else is read
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) reset()
            return
        }
        if (app.settings.overlayPaused) { reset(); return }
        inspectJob?.cancel()
        inspectJob = scope.launch {
            delay(if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) 1200 else 700)
            inspect(store)
        }
    }

    private fun inspect(store: Store) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != store.appPackage) return
        val found = runCatching { ScreenReader.read(root, resources.displayMetrics.heightPixels) }.getOrNull()
        if (found == null) {
            // tolerate a couple of glitchy reads (scrolling, pop-ups) before closing
            if (++notProductStreak >= 3) reset()
            return
        }
        notProductStreak = 0
        val cur = currentTitle
        if (cur != null && isSame(cur, found.title)) return
        if (found.title in dismissed) return
        // require the same new title twice in a row before switching (avoids flicker while scrolling)
        if (cur != null) {
            if (candidate != null && isSame(candidate!!, found.title)) candidateHits++ else { candidate = found.title; candidateHits = 1 }
            if (candidateHits < 2) return
        }
        startCompare(store, found)
    }

    private fun isSame(a: String, b: String) = a == b || Matcher.relevance(Matcher.queryFromTitle(a), b) >= 0.8

    private fun startCompare(store: Store, product: ScreenProduct) {
        currentTitle = product.title
        candidate = null
        compareJob?.cancel()
        overlay.show(store, product)
        val others = app.settings.enabledStores.value.filter { it != store }.sortedBy { it.ordinal }
        compareJob = scope.launch {
            app.repo.compareFlow(product.title, others).collect { overlay.update(it) }
        }
    }

    private fun reset() {
        inspectJob?.cancel()
        compareJob?.cancel()
        currentTitle = null
        candidate = null
        notProductStreak = 0
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onInterrupt() = reset()

    override fun onDestroy() {
        reset()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val s = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, ShoppingAssistService::class.java)
            return s.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
