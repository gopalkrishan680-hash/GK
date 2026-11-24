package com.gk.pricehunt.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.gk.pricehunt.MainActivity
import com.gk.pricehunt.data.Repository.CompareRow
import com.gk.pricehunt.data.Store
import com.gk.pricehunt.data.StoreStatus
import com.gk.pricehunt.scrape.Matcher
import com.gk.pricehunt.util.openInStore
import com.gk.pricehunt.util.rupees

/**
 * Floating UI drawn over the shopping app: a small bubble at the screen edge that expands
 * into a card listing the price of the same item on the other platforms.
 * Uses TYPE_ACCESSIBILITY_OVERLAY, so no extra "draw over apps" permission is needed.
 */
class CompareOverlay(private val ctx: Context, private val onDismiss: (String) -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null

    private var store: Store? = null
    private var product: ScreenProduct? = null
    private var rows: Map<Store, CompareRow> = emptyMap()

    val isShowing get() = bubble != null

    fun show(store: Store, product: ScreenProduct) {
        this.store = store
        this.product = product
        this.rows = emptyMap()
        collapse()
        ensureBubble()
        refreshBubble()
    }

    fun update(rows: Map<Store, CompareRow>) {
        this.rows = rows
        refreshBubble()
        if (panel != null) { removePanel(); openPanel() }
    }

    fun hide() {
        collapse()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        product = null
    }

    // ------------------------------------------------------------------ bubble

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureBubble() {
        if (bubble != null) return
        val tv = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(10), dp(8))
            elevation = dp(6).toFloat()
            maxWidth = dp(190)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = (ctx.resources.displayMetrics.heightPixels * 0.32).toInt()
        }
        var downY = 0f
        var startY = 0
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) { p.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(v, p) } }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        runCatching { wm.addView(tv, p) }.onSuccess { bubble = tv; bubbleParams = p }
    }

    private fun refreshBubble() {
        val tv = bubble ?: return
        val cur = product?.price
        val matches = rows.values.mapNotNull { it.match }
        val cheapest = matches.minByOrNull { it.price }
        val loading = rows.isEmpty() || rows.values.any { it.status is StoreStatus.Loading }
        val (text, color) = when {
            cheapest != null && cur != null && cheapest.price < cur ->
                "▼ ${rupees(cur - cheapest.price)} less on ${cheapest.store.displayName}" to 0xFF16A34A.toInt()
            loading -> "₹ Checking other apps…" to 0xFF4F46E5.toInt()
            cheapest != null && cur != null -> "✓ Best price is here" to 0xFF4F46E5.toInt()
            cheapest != null -> "From ${rupees(cheapest.price)} on ${cheapest.store.displayName}" to 0xFF4F46E5.toInt()
            else -> "₹ No match elsewhere" to 0xFF6B7280.toInt()
        }
        tv.text = text
        tv.background = GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f, dp(20).toFloat(), dp(20).toFloat()
            )
        }
    }

    // ------------------------------------------------------------------ panel

    private fun togglePanel() { if (panel != null) collapse() else openPanel() }

    private fun collapse() = removePanel()

    private fun removePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        val st = store ?: return
        val pr = product ?: return
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() }
            elevation = dp(12).toFloat()
        }

        // header
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("PriceHunt · Prices on other apps", 15f, 0xFF111827.toInt(), bold = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("✕", 18f, 0xFF6B7280.toInt()).apply {
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { onDismiss(pr.title); hide() }
        })
        card.addView(header)
        card.addView(text(pr.title, 12f, 0xFF6B7280.toInt(), maxLines = 1).apply { setPadding(0, dp(2), 0, dp(8)) })

        // current app row
        card.addView(row(st, "On this app", pr.price?.let { rupees(it) } ?: "—", null, highlight = false, onClick = null))

        val sorted = rows.values.sortedWith(compareBy<CompareRow> { it.match == null }.thenBy { it.match?.price ?: Double.MAX_VALUE })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val minPrice = sorted.mapNotNull { it.match?.price }.minOrNull()
        sorted.forEach { r ->
            val m = r.match
            when {
                m != null -> {
                    val diff = pr.price?.let { m.price - it }
                    val diffText = when {
                        diff == null -> null
                        diff < 0 -> "${rupees(-diff)} less"
                        diff > 0 -> "+${rupees(diff)}"
                        else -> "same"
                    }
                    list.addView(row(r.store, m.title, rupees(m.price), diffText,
                        highlight = m.price == minPrice && (pr.price == null || m.price < pr.price),
                        onClick = { collapse(); openInStore(ctx, m.url, r.store) }))
                }
                r.status is StoreStatus.Loading -> list.addView(row(r.store, "Checking…", "", null, false, null, loading = true))
                r.status is StoreStatus.Done -> list.addView(row(r.store, "Same item not found", "", null, false, null))
                else -> list.addView(row(r.store, "Couldn't check right now", "", null, false, null))
            }
        }
        val scroll = ScrollView(ctx).apply { addView(list) }
        card.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.42).toInt()
        scroll.post { if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH } }

        card.addView(text("Open full comparison in PriceHunt  →", 13f, 0xFF4F46E5.toInt(), bold = true).apply {
            setPadding(0, dp(10), 0, dp(2))
            setOnClickListener {
                collapse()
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SEARCH_QUERY, Matcher.queryFromTitle(pr.title))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
        })

        val frame = FrameLayout(ctx).apply { addView(card) }
        val p = WindowManager.LayoutParams(
            ctx.resources.displayMetrics.widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(84)
        }
        frame.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) collapse(); false }
        runCatching { wm.addView(frame, p) }.onSuccess { panel = frame }
    }

    private fun row(
        store: Store, title: String, price: String, diff: String?, highlight: Boolean,
        onClick: (() -> Unit)?, loading: Boolean = false,
    ): View {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(8), dp(9))
            if (highlight) background = GradientDrawable().apply { setColor(0xFFDCFCE7.toInt()); cornerRadius = dp(10).toFloat() }
            if (onClick != null) setOnClickListener { onClick() }
        }
        r.addView(text(store.displayName, 11f, Color.WHITE, bold = true).apply {
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = GradientDrawable().apply { setColor(store.color.toInt()); cornerRadius = dp(6).toFloat() }
            minWidth = dp(64)
            gravity = Gravity.CENTER
        })
        r.addView(text(title, 12f, 0xFF374151.toInt(), maxLines = 1).apply { setPadding(dp(8), 0, dp(8), 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (loading) {
            r.addView(ProgressBar(ctx).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(18), dp(18)))
        } else {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            col.addView(text(price, 15f, if (highlight) 0xFF15803D.toInt() else 0xFF111827.toInt(), bold = true))
            if (diff != null) col.addView(text(diff, 11f,
                if (diff.endsWith("less")) 0xFF15803D.toInt() else if (diff == "same") 0xFF6B7280.toInt() else 0xFFDC2626.toInt()))
            r.addView(col)
            if (onClick != null) r.addView(text("  ›", 18f, 0xFF9CA3AF.toInt()))
        }
        return r
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 2) = TextView(ctx).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
    }
}
