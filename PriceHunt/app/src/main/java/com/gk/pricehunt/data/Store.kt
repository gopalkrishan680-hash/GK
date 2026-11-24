package com.gk.pricehunt.data

import java.net.URLEncoder

/**
 * Every supported shopping site. [productLinkRegex] must match product-page URLs
 * and capture a stable product id in group 1 (used to de-duplicate and track history).
 */
enum class Store(
    val id: String,
    val displayName: String,
    val color: Long,
    val appPackage: String?,
    val hostHint: String,
    val productLinkRegex: String,
    private val searchTemplate: (String) -> String,
    val enabledByDefault: Boolean = true,
) {
    AMAZON(
        "amazon", "Amazon", 0xFFFF9900, "in.amazon.mShop.android.shopping", "amazon.",
        """/(?:dp|gp/product|gp/aw/d)/([A-Z0-9]{10})""",
        { q -> "https://www.amazon.in/s?k=${enc(q)}" },
    ),
    FLIPKART(
        "flipkart", "Flipkart", 0xFF2874F0, "com.flipkart.android", "flipkart.",
        """flipkart\.com/[^?#]*/p/(itm[a-zA-Z0-9]+)""",
        { q -> "https://www.flipkart.com/search?q=${enc(q)}" },
    ),
    MYNTRA(
        "myntra", "Myntra", 0xFFFF3F6C, "com.myntra.android", "myntra.",
        """myntra\.com/(?:[^?#]+/)?(\d{5,})/buy""",
        { q -> "https://www.myntra.com/${slug(q)}?rawQuery=${enc(q)}" },
    ),
    MEESHO(
        "meesho", "Meesho", 0xFF9F2089, "com.meesho.supply", "meesho.",
        """meesho\.com/[^?#]*/p/([a-zA-Z0-9]+)""",
        { q -> "https://www.meesho.com/search?q=${enc(q)}" },
    ),
    AJIO(
        "ajio", "AJIO", 0xFF2C4152, "com.ril.ajio", "ajio.",
        """ajio\.com/[^?#]*/p/([a-zA-Z0-9_]+)""",
        { q -> "https://www.ajio.com/search/?text=${enc(q)}" },
    ),
    CROMA(
        "croma", "Croma", 0xFF00A79D, null, "croma.",
        """croma\.com/[^?#]*/p/(\d+)""",
        { q -> "https://www.croma.com/searchB?q=${enc(q)}%3Arelevance&text=${enc(q)}" },
    ),
    TATACLIQ(
        "tatacliq", "Tata CLiQ", 0xFFDA1C5C, "com.tul.tatacliq", "tatacliq.",
        """tatacliq\.com/[^?#]*/p-(mp\d+)""",
        { q -> "https://www.tatacliq.com/search/?searchCategory=all&text=${enc(q)}" },
        enabledByDefault = false,
    ),
    SNAPDEAL(
        "snapdeal", "Snapdeal", 0xFFE40046, "com.snapdeal.main", "snapdeal.",
        """snapdeal\.com/product/[^/?#]+/(\d+)""",
        { q -> "https://www.snapdeal.com/search?keyword=${enc(q)}" },
        enabledByDefault = false,
    ),
    JIOMART(
        "jiomart", "JioMart", 0xFF0078AD, "com.jpl.jiomart", "jiomart.",
        """jiomart\.com/p/[^?#]*/(\d+)""",
        { q -> "https://www.jiomart.com/search/${enc(q)}" },
        enabledByDefault = false,
    );

    fun searchUrl(query: String) = searchTemplate(query.trim())

    private val compiled by lazy { Regex(productLinkRegex, RegexOption.IGNORE_CASE) }

    /** Stable product key from a product URL, or null if the URL isn't a product page of this store. */
    fun productKey(url: String): String? = compiled.find(url)?.groupValues?.getOrNull(1)

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
        fun fromPackage(pkg: String) = entries.firstOrNull { it.appPackage == pkg }
        fun fromUrl(url: String): Store? {
            val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return null
            return entries.firstOrNull { host.contains(it.hostHint) }
        }
    }
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "search" }
