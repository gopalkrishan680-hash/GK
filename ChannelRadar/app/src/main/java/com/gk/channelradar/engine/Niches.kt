package com.gk.channelradar.engine

import com.gk.channelradar.data.ScanConfig

data class Niche(val name: String, val emoji: String, val keywords: List<String>)

object Niches {
    val all: List<Niche> = listOf(
        Niche("Tech", "📱", listOf("smartphone review", "tech tips", "unboxing", "best phone under", "laptop review", "android tricks", "gadgets")),
        Niche("AI Tools", "🤖", listOf("ai tools", "chatgpt", "ai video generator", "ai se paise", "ai tutorial", "ai image")),
        Niche("Finance", "💰", listOf("stock market", "share market", "mutual fund", "paise kaise kamaye", "trading strategy", "personal finance", "crypto")),
        Niche("Business", "🏪", listOf("business ideas", "side hustle", "small business", "online earning", "startup story", "dropshipping")),
        Niche("Gaming", "🎮", listOf("gameplay", "bgmi", "free fire", "minecraft", "gta", "gaming highlights", "mobile game")),
        Niche("Education", "📚", listOf("study tips", "upsc", "ssc exam", "class 10", "english speaking", "math tricks", "science explained")),
        Niche("Facts", "🧠", listOf("amazing facts", "facts in hindi", "mystery", "unknown facts", "documentary", "history explained")),
        Niche("Story / Kahani", "📖", listOf("kahani", "moral story", "horror story", "real story", "cartoon story", "motivational story")),
        Niche("Comedy", "😂", listOf("comedy video", "funny video", "prank", "comedy sketch", "desi comedy", "roast")),
        Niche("Vlogs", "🎥", listOf("vlog", "daily vlog", "village life", "family vlog", "day in my life", "lifestyle")),
        Niche("Cooking", "🍳", listOf("recipe", "street food", "easy recipe", "khana", "food review", "baking")),
        Niche("Health & Fitness", "💪", listOf("workout", "weight loss", "yoga", "home workout", "diet plan", "health tips")),
        Niche("Motivation", "🔥", listOf("motivation", "self improvement", "success story", "life lessons", "discipline", "podcast clips")),
        Niche("News & Politics", "📰", listOf("news analysis", "current affairs", "explained", "geopolitics", "breaking news")),
        Niche("Movies & Reviews", "🎬", listOf("movie explained", "movie review", "web series review", "film recap", "anime explained")),
        Niche("Music", "🎵", listOf("song cover", "lofi", "new song", "bhajan", "remix", "music production")),
        Niche("Devotional", "🙏", listOf("bhakti", "krishna", "shiv", "hanuman", "spiritual", "astrology")),
        Niche("Travel", "✈️", listOf("travel vlog", "budget trip", "trek", "travel guide", "road trip")),
        Niche("Auto", "🚗", listOf("car review", "bike review", "modified", "ev scooter", "car tips")),
        Niche("Beauty & Fashion", "💄", listOf("makeup tutorial", "skincare", "outfit ideas", "hairstyle", "fashion haul")),
        Niche("DIY & Crafts", "🛠️", listOf("diy", "craft ideas", "life hacks", "experiment", "woodworking", "home decor")),
        Niche("Farming", "🌾", listOf("kheti", "farming", "organic farming", "dairy farm", "gardening")),
        Niche("Sports", "🏏", listOf("cricket", "football", "ipl", "match highlights", "cricket analysis")),
        Niche("Kids & Animation", "🧸", listOf("kids story", "animation", "rhymes", "cartoon", "3d animation")),
        Niche("Pets & Animals", "🐶", listOf("dog", "cat", "animal rescue", "wildlife", "pet care")),
        Niche("Coding", "💻", listOf("coding tutorial", "python", "web development", "programming", "app development")),
        Niche("Real Estate", "🏠", listOf("house tour", "home design", "property", "construction", "interior design")),
        Niche("Relationships", "❤️", listOf("relationship advice", "psychology facts", "love tips", "couple")),
    )

    fun byName(name: String): Niche? = all.firstOrNull { it.name == name }

    fun nicheOfKeyword(kw: String): String =
        all.firstOrNull { n -> n.keywords.any { it.equals(kw, true) } }?.name ?: "Custom"

    /**
     * Picks a diverse set of search keywords: round-robin across niches so a single
     * scan touches many different niches instead of five keywords from one niche.
     */
    fun pickKeywords(cfg: ScanConfig): List<Pair<String, String>> {
        val custom = cfg.customKeywords.split(',', '\n')
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            .map { it to "Custom" }
        val n = cfg.keywordsPerScan.coerceAtLeast(1)
        if (custom.isNotEmpty() && cfg.niches.isEmpty()) return custom.take(n.coerceAtLeast(custom.size).coerceAtMost(30))

        val niches = (if (cfg.niches.isEmpty()) all else cfg.niches.mapNotNull { byName(it) }).shuffled()
        val pools = niches.map { it.name to it.keywords.shuffled().toMutableList() }
        val out = custom.take(n).toMutableList()
        var i = 0
        while (out.size < n && pools.any { it.second.isNotEmpty() }) {
            val (name, pool) = pools[i % pools.size]
            if (pool.isNotEmpty()) out += pool.removeAt(0) to name
            i++
        }
        return out
    }

    /** YouTube video categories used for the trending (mostPopular) chart sweep. */
    val trendingCategories: List<Pair<String?, String>> = listOf(
        null to "All",
        "20" to "Gaming",
        "22" to "People & Blogs",
        "23" to "Comedy",
        "24" to "Entertainment",
        "26" to "Howto & Style",
        "27" to "Education",
        "28" to "Science & Tech",
        "10" to "Music",
        "17" to "Sports",
        "25" to "News",
        "1" to "Film & Animation",
        "2" to "Autos",
        "15" to "Pets & Animals",
    )
}
