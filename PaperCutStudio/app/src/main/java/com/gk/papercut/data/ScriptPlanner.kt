package com.gk.papercut.data

import java.util.UUID

/**
 * Offline "director": splits the script into scenes, spots the things each scene talks about
 * (English, Hinglish and Hindi words), picks animations from the verbs, and writes a
 * paper-cut image prompt for every scene.
 */
object ScriptPlanner {

    private const val MAX_WORDS_PER_SCENE = 28
    private const val TARGET_WORDS_PER_SCENE = 18

    fun words(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotBlank() }

    fun normalize(w: String) = w.lowercase().trim { !it.isLetterOrDigit() }

    fun splitScenes(script: String): List<String> {
        val clean = script.replace("\r", "").trim()
        if (clean.isEmpty()) return emptyList()
        val paragraphs = clean.split(Regex("\\n\\s*\\n")).map { it.replace("\n", " ").trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        for (p in paragraphs) {
            if (paragraphs.size > 1 && words(p).size <= MAX_WORDS_PER_SCENE) { out += p; continue }
            // Group sentences into scenes of roughly TARGET_WORDS_PER_SCENE words.
            val sentences = p.split(Regex("(?<=[.!?।|])\\s+")).filter { it.isNotBlank() }
            var cur = StringBuilder()
            for (s in sentences) {
                val curWords = words(cur.toString()).size
                if (curWords > 0 && curWords + words(s).size > MAX_WORDS_PER_SCENE ||
                    curWords >= TARGET_WORDS_PER_SCENE) {
                    out += cur.toString().trim(); cur = StringBuilder()
                }
                cur.append(s).append(' ')
            }
            if (cur.isNotBlank()) out += cur.toString().trim()
        }
        // Split any remaining monster sentence by word count.
        return out.flatMap { s ->
            val w = words(s)
            if (w.size <= MAX_WORDS_PER_SCENE * 3 / 2) listOf(s)
            else w.chunked(TARGET_WORDS_PER_SCENE).map { it.joinToString(" ") }
        }
    }

    fun plan(project: Project): List<Scene> {
        val texts = splitScenes(project.script)
        return texts.mapIndexed { i, t ->
            val hints = findElements(t)
            Scene(
                id = UUID.randomUUID().toString().take(8),
                text = t,
                elementsHint = hints,
                prompt = buildPrompt(project, t, hints),
                camera = CameraMove.entries.filter { it != CameraMove.STATIC }[i % 4],
                transition = if (i % 3 == 2) Transition.PAPER_FLIP else Transition.PAPER_SLIDE,
            )
        }
    }

    fun buildPrompt(project: Project, text: String, hints: List<ElementHint>): String = buildString {
        append("Layered paper cut-out craft illustration, ${project.aspect.ratio} frame. ")
        append("Scene to illustrate: \"${text.trim()}\". ")
        if (hints.isNotEmpty()) append("Show clearly: ${hints.joinToString(", ") { it.name }}. ")
        if (project.characterNote.isNotBlank()) append("Characters (keep identical in every scene): ${project.characterNote.trim()}. ")
        if (project.styleNote.isNotBlank()) append("Style: ${project.styleNote.trim()}. ")
        append(STYLE_SUFFIX)
    }

    const val STYLE_SUFFIX =
        "Handmade colored construction paper with visible paper grain, flat colors, soft drop shadows between paper layers. " +
            "Every character and object is a separate clean cut-out with a thin white paper edge, placed apart from each other " +
            "(not overlapping), with empty space around them, on a plain off-white paper background. " +
            "No text, no letters, no captions, no watermark."

    /** A prompt the user can paste into any chatbot to get richer per-scene prompts back. */
    fun chatbotHelperPrompt(project: Project, scenes: List<Scene>): String = buildString {
        appendLine("You are an art director for a paper cut-out animation video (${project.aspect.ratio}).")
        appendLine("For each numbered scene below, write ONE detailed English image prompt for an image generator.")
        appendLine("Rules: layered handmade paper cut-out style, visible paper texture, soft shadows, every character/object is a separate cut-out with a thin white edge, elements must NOT overlap, simple background, no text in the image.")
        if (project.characterNote.isNotBlank()) appendLine("Keep these characters identical in every scene: ${project.characterNote}")
        if (project.styleNote.isNotBlank()) appendLine("Overall style: ${project.styleNote}")
        appendLine("Answer as a numbered list with the same numbers.")
        appendLine()
        scenes.forEachIndexed { i, s -> appendLine("${i + 1}. ${s.text}") }
    }

    fun allPromptsText(project: Project): String = buildString {
        appendLine("${project.name} - image prompts (${project.scenes.size} scenes, ${project.aspect.ratio})")
        appendLine("Save each image as scene_01, scene_02 ... then use 'Import all images' in the app.")
        appendLine()
        project.scenes.forEachIndexed { i, s ->
            appendLine("=== Scene ${i + 1} ===")
            appendLine(s.prompt)
            appendLine()
        }
    }

    // ---------------------------------------------------------------- element & motion spotting

    private data class Noun(val name: String, val enter: EnterAnim, val idle: IdleAnim)

    private val nouns: Map<String, Noun> = buildMap {
        fun add(name: String, enter: EnterAnim, idle: IdleAnim, vararg keys: String) =
            keys.forEach { put(it, Noun(name, enter, idle)) }
        add("a boy", EnterAnim.SLIDE_LEFT, IdleAnim.BOB, "boy", "ladka", "ladke", "लड़का", "लड़के", "beta", "बेटा")
        add("a girl", EnterAnim.SLIDE_RIGHT, IdleAnim.BOB, "girl", "ladki", "लड़की", "beti", "बेटी")
        add("a man", EnterAnim.SLIDE_LEFT, IdleAnim.BOB, "man", "aadmi", "आदमी", "uncle", "chacha", "चाचा")
        add("a woman", EnterAnim.SLIDE_RIGHT, IdleAnim.BOB, "woman", "aurat", "औरत", "aunty", "chachi", "चाची")
        add("a father", EnterAnim.SLIDE_LEFT, IdleAnim.BOB, "father", "papa", "पापा", "pitaji", "पिताजी", "dad")
        add("a mother", EnterAnim.SLIDE_RIGHT, IdleAnim.BOB, "mother", "mummy", "मम्मी", "maa", "माँ", "mom")
        add("a grandfather", EnterAnim.SLIDE_LEFT, IdleAnim.SWAY, "grandfather", "dadu", "dada", "दादा", "nana", "नाना")
        add("a grandmother", EnterAnim.SLIDE_RIGHT, IdleAnim.SWAY, "grandmother", "dadi", "दादी", "nani", "नानी")
        add("a child", EnterAnim.POP, IdleAnim.BOB, "child", "kid", "baby", "bachcha", "bacha", "बच्चा", "बच्चे")
        add("friends", EnterAnim.POP, IdleAnim.BOB, "friend", "friends", "dost", "दोस्त", "yaar")
        add("a teacher", EnterAnim.SLIDE_LEFT, IdleAnim.BOB, "teacher", "sir", "madam", "मास्टर")
        add("a doctor", EnterAnim.SLIDE_LEFT, IdleAnim.BOB, "doctor", "डॉक्टर")
        add("a house", EnterAnim.GROW_UP, IdleAnim.NONE, "house", "home", "ghar", "घर", "makaan")
        add("a school building", EnterAnim.GROW_UP, IdleAnim.NONE, "school", "स्कूल", "college")
        add("an office building", EnterAnim.GROW_UP, IdleAnim.NONE, "office", "ऑफिस", "company")
        add("a shop", EnterAnim.GROW_UP, IdleAnim.NONE, "shop", "dukaan", "dukan", "दुकान", "market", "bazaar")
        add("a city skyline", EnterAnim.GROW_UP, IdleAnim.NONE, "city", "shahar", "शहर")
        add("a village", EnterAnim.GROW_UP, IdleAnim.NONE, "village", "gaon", "gaanv", "गाँव", "गांव")
        add("a tree", EnterAnim.GROW_UP, IdleAnim.SWAY, "tree", "ped", "पेड़", "jungle", "forest")
        add("flowers", EnterAnim.GROW_UP, IdleAnim.SWAY, "flower", "flowers", "phool", "फूल")
        add("a sun", EnterAnim.SLIDE_UP, IdleAnim.SPIN, "sun", "sooraj", "suraj", "सूरज", "subah", "morning")
        add("a moon", EnterAnim.DROP, IdleAnim.FLOAT, "moon", "chand", "chaand", "चाँद", "raat", "रात", "night")
        add("stars", EnterAnim.POP, IdleAnim.PULSE, "star", "stars", "tara", "taare", "तारे")
        add("clouds", EnterAnim.SLIDE_RIGHT, IdleAnim.FLOAT, "cloud", "clouds", "badal", "बादल", "sky", "aasman", "आसमान")
        add("rain drops", EnterAnim.DROP, IdleAnim.FLOAT, "rain", "baarish", "barish", "बारिश")
        add("a car", EnterAnim.SLIDE_LEFT, IdleAnim.SHAKE, "car", "gaadi", "gadi", "गाड़ी", "taxi")
        add("a bus", EnterAnim.SLIDE_LEFT, IdleAnim.SHAKE, "bus", "बस")
        add("a bicycle", EnterAnim.SLIDE_LEFT, IdleAnim.BOB, "cycle", "bicycle", "साइकिल", "bike")
        add("a train", EnterAnim.SLIDE_RIGHT, IdleAnim.SHAKE, "train", "ट्रेन", "rail")
        add("an airplane", EnterAnim.SLIDE_RIGHT, IdleAnim.FLOAT, "plane", "airplane", "jahaz", "जहाज")
        add("money notes and coins", EnterAnim.DROP, IdleAnim.PULSE, "money", "paisa", "paise", "पैसा", "पैसे", "rupaye", "rupees", "रुपये", "salary", "cash")
        add("a smartphone", EnterAnim.POP, IdleAnim.PULSE, "phone", "mobile", "फोन", "smartphone")
        add("a laptop", EnterAnim.POP, IdleAnim.NONE, "laptop", "computer", "लैपटॉप")
        add("a book", EnterAnim.FLIP, IdleAnim.FLOAT, "book", "books", "kitab", "kitaab", "किताब", "padhai", "पढ़ाई")
        add("a clock", EnterAnim.SPIN_IN, IdleAnim.PULSE, "clock", "time", "ghadi", "घड़ी", "samay", "समय", "waqt")
        add("a dog", EnterAnim.SLIDE_RIGHT, IdleAnim.BOB, "dog", "kutta", "कुत्ता")
        add("a cat", EnterAnim.SLIDE_RIGHT, IdleAnim.BOB, "cat", "billi", "बिल्ली")
        add("a cow", EnterAnim.SLIDE_LEFT, IdleAnim.SWAY, "cow", "gaay", "gai", "गाय")
        add("a bird", EnterAnim.SLIDE_RIGHT, IdleAnim.FLOAT, "bird", "chidiya", "चिड़िया", "parinda")
        add("food plates", EnterAnim.POP, IdleAnim.NONE, "food", "khana", "खाना", "roti", "रोटी", "chai", "चाय", "tea")
        add("water", EnterAnim.FADE, IdleAnim.FLOAT, "water", "pani", "paani", "पानी", "river", "nadi", "नदी")
        add("a road", EnterAnim.FADE, IdleAnim.NONE, "road", "sadak", "सड़क", "rasta", "रास्ता")
        add("a mountain", EnterAnim.GROW_UP, IdleAnim.NONE, "mountain", "pahad", "पहाड़", "hill")
        add("a heart", EnterAnim.POP, IdleAnim.PULSE, "love", "pyaar", "pyar", "प्यार", "heart", "dil", "दिल")
        add("a light bulb idea", EnterAnim.POP, IdleAnim.PULSE, "idea", "socha", "सोचा", "plan")
        add("a trophy", EnterAnim.DROP, IdleAnim.PULSE, "trophy", "win", "jeet", "जीत", "success", "kamyab")
        add("a gift box", EnterAnim.DROP, IdleAnim.SHAKE, "gift", "tohfa", "तोहफा", "birthday")
        add("a wedding mandap", EnterAnim.GROW_UP, IdleAnim.NONE, "shaadi", "शादी", "wedding")
        add("a hospital", EnterAnim.GROW_UP, IdleAnim.NONE, "hospital", "अस्पताल")
        add("a temple", EnterAnim.GROW_UP, IdleAnim.NONE, "temple", "mandir", "मंदिर")
    }

    /** Verbs nudge the animation of the element mentioned in the same scene. */
    private val verbs: Map<String, Pair<EnterAnim?, IdleAnim?>> = buildMap {
        fun add(e: EnterAnim?, i: IdleAnim?, vararg keys: String) = keys.forEach { put(it, e to i) }
        add(EnterAnim.SLIDE_LEFT, IdleAnim.WALK, "run", "running", "walk", "walking", "daud", "daude", "दौड़", "chal", "chala", "चला", "gaya", "गया", "aaya", "आया")
        add(EnterAnim.DROP, IdleAnim.SHAKE, "fall", "fell", "gir", "gira", "गिरा", "gire")
        add(EnterAnim.GROW_UP, null, "grow", "grew", "badh", "badha", "बढ़ा", "bada")
        add(EnterAnim.POP, IdleAnim.BOB, "jump", "kood", "कूद", "uchhal")
        add(EnterAnim.SLIDE_RIGHT, IdleAnim.FLOAT, "fly", "flying", "ud", "uda", "उड़")
        add(null, IdleAnim.SHAKE, "angry", "gussa", "गुस्सा", "scared", "dar", "डर", "fight", "ladai", "लड़ाई")
        add(null, IdleAnim.PULSE, "happy", "khush", "खुश", "excited", "hasna", "हँसा", "smile")
        add(null, IdleAnim.SWAY, "sad", "dukhi", "दुखी", "roya", "रोया", "cry", "think", "soch", "सोच")
        add(EnterAnim.SPIN_IN, IdleAnim.SPIN, "spin", "ghuma", "घूमा", "round")
    }

    fun findElements(text: String): List<ElementHint> {
        val ws = words(text).map(::normalize)
        var verb: Pair<EnterAnim?, IdleAnim?>? = null
        ws.forEach { w -> verbs[w]?.let { verb = it } }
        val found = LinkedHashMap<String, ElementHint>()
        ws.forEachIndexed { idx, w ->
            val n = nouns[w] ?: return@forEachIndexed
            if (n.name in found) return@forEachIndexed
            val people = n.idle == IdleAnim.BOB && n.enter in setOf(EnterAnim.SLIDE_LEFT, EnterAnim.SLIDE_RIGHT, EnterAnim.POP)
            val enter = if (people) verb?.first ?: n.enter else n.enter
            val idle = if (people) verb?.second ?: n.idle else n.idle
            found[n.name] = ElementHint(n.name, idx, enter, idle)
        }
        return found.values.take(5)
    }
}
