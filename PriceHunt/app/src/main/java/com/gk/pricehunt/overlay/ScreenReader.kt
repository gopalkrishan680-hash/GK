package com.gk.pricehunt.overlay

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** What we could read from a shopping app's product page. */
data class ScreenProduct(val title: String, val price: Double?)

/**
 * Reads the product title and price from the screen of a shopping app using the
 * accessibility tree. Only called for the shopping apps' own windows.
 */
object ScreenReader {
    data class Item(val text: String, val top: Int, val bottom: Int)

    private val buyNow = Regex("""(?i)^\s*(buy now|buy at|buy with)""")
    private val addTo = Regex("""(?i)^\s*(add to cart|add to bag|add to basket|go to cart|go to bag)\s*$""")
    private val priceRe = Regex("""(?:₹|Rs\.?|INR)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""")
    private val noise = Regex(
        """(?i)\b(offer|offers|delivery|deliver|cashback|emi|bank|coupon|review|reviews|rating|ratings|return|returns|warranty|seller|similar|sponsored|pincode|size chart|select size|highlights|description|wishlist|share|cart|bag|save|off\b|exchange|protect|specifications|frequently|customers|questions|visit the|brand:|store|sold by|in stock|left in stock|bought|available|days|apply|view|check|terms)\b"""
    )

    fun read(root: AccessibilityNodeInfo, screenHeight: Int): ScreenProduct? {
        val items = ArrayList<Item>(256)
        collect(root, items, 0)
        return parse(items, screenHeight)
    }

    /** Pure part of [read] (unit-tested): screen texts in reading order -> product. */
    fun parse(items: List<Item>, screenHeight: Int): ScreenProduct? {
        if (items.isEmpty()) return null

        val buyCount = items.count { buyNow.containsMatchIn(it.text) && it.text.length < 30 }
        val addCount = items.count { addTo.matches(it.text) }
        // product page = has a "Buy now", or exactly one "Add to cart/bag" (search lists have many)
        if (buyCount == 0 && addCount != 1) return null

        val prices = items.filter { it.text.length <= 18 }.mapNotNull { i ->
            priceRe.find(i.text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.let { i to it }
        }.filter { it.first.top in 0..screenHeight }

        val candidates = items.filter { i ->
            val words = i.text.split(' ').count { it.isNotBlank() }
            i.text.length in 15..260 && words >= 3 && !i.text.contains('₹') &&
                !noise.containsMatchIn(i.text) && i.top in 0..(screenHeight * 0.85).toInt() &&
                !i.text.endsWith("?") && !i.text.startsWith("http")
        }
        if (candidates.isEmpty()) return null

        // A good title is fairly long and sits close to the main price.
        val best = candidates.maxByOrNull { c ->
            val near = prices.minOfOrNull { kotlin.math.abs(it.first.top - c.bottom) } ?: screenHeight
            (c.text.length.coerceAtMost(110) / 110.0) - near.toDouble() / screenHeight * 1.6
        } ?: return null

        // Selling price: the smallest price in the first cluster of prices after the title
        // (the MRP is always the bigger one next to it).
        val after = prices.filter { it.first.top >= best.top - 20 }.sortedBy { it.first.top }
        val price = after.firstOrNull()?.let { first ->
            after.filter { it.first.top - first.first.top < 260 }.take(3).minOf { it.second }
        }
        return ScreenProduct(best.text.replace(Regex("\\s+"), " ").trim(), price)
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Item>, depth: Int) {
        if (n == null || depth > 40 || out.size > 700) return
        if (!n.isVisibleToUser) return
        val t = (n.text ?: n.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length < 400) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) out += Item(t, r.top, r.bottom)
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }
}
