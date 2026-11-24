package com.gk.pricehunt

import com.gk.pricehunt.data.Store
import com.gk.pricehunt.overlay.ScreenReader
import com.gk.pricehunt.overlay.ScreenReader.Item
import com.gk.pricehunt.scrape.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun screen(vararg rows: Pair<String, Int>) = rows.map { Item(it.first, it.second, it.second + 50) }

    @Test fun amazonProductPage() {
        val p = ScreenReader.parse(screen(
            "Visit the boAt Store" to 200,
            "boAt Airdopes 141 Bluetooth TWS Earbuds with 42H Playtime, Low Latency Mode (Bold Black)" to 260,
            "4.0 out of 5 stars" to 380,
            "-73%" to 900, "₹1,099" to 900, "M.R.P.:" to 960, "₹4,490" to 960,
            "Inclusive of all taxes" to 1010,
            "Bank Offer: Upto ₹1,000 off on HDFC cards" to 1100,
            "Add to Cart" to 2100, "Buy Now" to 2200,
        ), 2400)!!
        assertTrue(p.title.startsWith("boAt Airdopes 141"))
        assertEquals(1099.0, p.price!!, 0.1)
    }

    @Test fun flipkartProductPage() {
        val p = ScreenReader.parse(screen(
            "SAMSUNG Galaxy S24 5G (Onyx Black, 256 GB)" to 1300,
            "8 GB RAM" to 1360,
            "71% off" to 1420, "₹89,999" to 1420, "₹64,999" to 1420,
            "Free delivery by Tomorrow" to 1600,
            "Add to cart" to 2250, "Buy now" to 2250,
        ), 2400)!!
        assertEquals("SAMSUNG Galaxy S24 5G (Onyx Black, 256 GB)", p.title)
        assertEquals(64999.0, p.price!!, 0.1)
    }

    @Test fun searchListIsNotAProductPage() {
        val p = ScreenReader.parse(screen(
            "boAt Airdopes 141 earbuds black" to 300, "₹1,099" to 360, "Add to cart" to 400,
            "boAt Airdopes 161 earbuds white" to 700, "₹1,199" to 760, "Add to cart" to 800,
        ), 2400)
        assertNull(p)
    }

    @Test fun variantConflictLowersScore() {
        val q = "Apple iPhone 15 128 GB Black"
        assertTrue(Matcher.score(q, "Apple iPhone 15 (Black, 128 GB)") > 0.9)
        assertTrue(Matcher.score(q, "Apple iPhone 15 (Black, 256 GB)") < 0.7)
        assertTrue(Matcher.score("Fortune sunflower oil 1 l", "Fortune Sunlite Refined Sunflower Oil 5 L Jar") < 0.6)
    }

    @Test fun storeKeys() {
        assertEquals("B0ABCDEF01", Store.AMAZON.productKey("https://www.amazon.in/boAt-Airdopes/dp/B0ABCDEF01/ref=sr_1_1"))
        assertEquals("itmabc123", Store.FLIPKART.productKey("https://www.flipkart.com/boat-airdopes/p/itmabc123?pid=ACC"))
        assertEquals("27542874", Store.MYNTRA.productKey("https://www.myntra.com/tshirts/puma/puma-men-tshirt/27542874/buy"))
        assertEquals("5abc1", Store.MEESHO.productKey("https://www.meesho.com/trendy-kurti/p/5abc1"))
        assertEquals(Store.FLIPKART, Store.fromPackage("com.flipkart.android"))
    }
}
