package com.gk.facelessstudio.model

import org.json.JSONArray
import org.json.JSONObject

enum class Aspect(val label: String, val sub: String, val ratioW: Int, val ratioH: Int) {
    LANDSCAPE("YouTube", "16:9", 16, 9),
    PORTRAIT("Shorts / Reels", "9:16", 9, 16),
    SQUARE("Square", "1:1", 1, 1);

    fun size(quality: Quality): Pair<Int, Int> {
        val short = quality.shortEdge
        return when (this) {
            LANDSCAPE -> Pair(short * 16 / 9, short)
            PORTRAIT -> Pair(short, short * 16 / 9)
            SQUARE -> Pair(short, short)
        }.let { (w, h) -> Pair(w / 2 * 2, h / 2 * 2) }
    }
}

enum class Quality(val label: String, val shortEdge: Int, val bitrate: Int) {
    HD("720p HD", 720, 6_000_000),
    FHD("1080p Full HD", 1080, 12_000_000)
}

enum class Pacing(val label: String, val maxSceneSec: Float, val transitionSec: Float) {
    CALM("Calm", 7.0f, 0.55f),
    BALANCED("Balanced", 5.0f, 0.42f),
    ENERGETIC("Energetic", 3.2f, 0.32f)
}

enum class CaptionMode(val label: String, val sub: String) {
    KEYWORDS("Keywords", "Only key words pop up — visuals do the talking"),
    FULL("Full captions", "Word-by-word subtitles for every line"),
    OFF("Off", "No captions"),
}

/** Every visual layout the app can generate. `needsImage` layouts fall back to text layouts when no image exists. */
enum class SceneType(val label: String, val needsImage: Boolean, val showsOwnText: Boolean) {
    KINETIC("Kinetic Words", false, true),
    CHAPTER("Chapter Title", false, true),
    KEN_BURNS("Cinematic Image", true, false),
    IMAGE_BANNER("Image + Banner", true, true),
    QUESTION_LIST("Typewriter List", false, true),
    CHECKLIST("Checklist Card", false, true),
    MINDMAP("Mind Map", false, true),
    PAPER_NOTE("Paper Note", false, true),
    BIG_STAT("Number Counter", false, true),
    DATE_STAMP("Date Stamp", false, false),
    VERSUS("Versus Split", false, true),
    QUOTE("Quote", false, true),
    SPLIT_ARROW("Cause → Effect", false, true),
    POLAROID("Photo Stack", true, false),
    HEADLINE_IMAGE("Headline on Image", true, true),
    PHONE("Phone Mockup", true, true),
    SPOTLIGHT("Spotlight Text", false, true),
    DONUT("Percent Donut", false, true),
    BAR_CHART("Bar Chart", false, true),
    TIMELINE("Timeline", false, true),
    KEY_POINTS("Key Points", false, true),
    OUTRO("Subscribe Outro", false, true);
}

enum class Transition(val label: String) {
    FADE("Fade"), ZOOM_PUNCH("Zoom Punch"), SLIDE_LEFT("Slide"), SLIDE_UP("Slide Up"),
    WHIP("Whip Pan"), FLASH("Flash"), GLITCH("Glitch"), CIRCLE("Circle Reveal"),
    SHUTTER("Shutter"), SPIN("Spin Zoom"), CUT("Hard Cut"), DIP("Dip to Black")
}

data class Scene(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val type: SceneType,
    val transition: Transition,
    val imageIndex: Int = -1,
    val variant: Int = 0,
    /** Extra structured content: list items, stat value, title etc. */
    val lines: List<String> = emptyList(),
    /** Relative (0..1) start of each line inside the scene, aligned to the voice. */
    val lineStarts: List<Float> = emptyList(),
    val title: String = "",
    val emphasis: List<String> = emptyList(),
    val chapterIndex: Int = -1,
    val locked: Boolean = false,
) {
    val durationMs get() = (endMs - startMs).coerceAtLeast(1)

    fun toJson(): JSONObject = JSONObject().apply {
        put("text", text); put("start", startMs); put("end", endMs)
        put("type", type.name); put("tr", transition.name); put("img", imageIndex)
        put("variant", variant); put("lines", JSONArray(lines))
        put("lineStarts", JSONArray(lineStarts.map { it.toDouble() }))
        put("title", title); put("emph", JSONArray(emphasis)); put("chapter", chapterIndex)
        put("locked", locked)
    }

    companion object {
        fun fromJson(o: JSONObject) = Scene(
            text = o.optString("text"),
            startMs = o.optLong("start"),
            endMs = o.optLong("end"),
            type = runCatching { SceneType.valueOf(o.optString("type")) }.getOrDefault(SceneType.KEN_BURNS),
            transition = runCatching { Transition.valueOf(o.optString("tr")) }.getOrDefault(Transition.FADE),
            imageIndex = o.optInt("img", -1),
            variant = o.optInt("variant"),
            lines = o.optJSONArray("lines").strings(),
            lineStarts = o.optJSONArray("lineStarts").strings().map { it.toFloatOrNull() ?: 0f },
            title = o.optString("title"),
            emphasis = o.optJSONArray("emph").strings(),
            chapterIndex = o.optInt("chapter", -1),
            locked = o.optBoolean("locked"),
        )
    }
}

data class Project(
    val id: String,
    val title: String = "",
    val script: String = "",
    val voicePath: String? = null,
    val voiceName: String? = null,
    val musicPath: String? = null,
    val musicName: String? = null,
    val musicVolume: Float = 0.18f,
    val images: List<String> = emptyList(),
    val aspect: Aspect = Aspect.LANDSCAPE,
    val themeId: String = "neon",
    val pacing: Pacing = Pacing.BALANCED,
    val captionMode: CaptionMode = CaptionMode.KEYWORDS,
    /** Story mode: cinematic full-screen pictures, real subtitle lines, soft transitions, no infographics. */
    val storyMode: Boolean = true,
    /** true when images were generated one-per-script-line, so scene N uses its own image. */
    val lineImages: Boolean = false,
    val imageStep: Int = 1,
    // ---- grid image workflow (prompts for the user's own ChatGPT / Gemini) ----
    val artStyle: String = "storybook",
    val styleDesc: String = "",
    val characters: String = "",
    /** auto / white / black / none */
    val separator: String = "auto",
    val gridSize: Int = 9,
    val directorPart: Int = 150,
    /** English visual description per scene piece (from the AI Scene Director reply). */
    val visuals: List<String> = emptyList(),
    /** Script piece texts the images/visuals belong to (to re-match after script edits). */
    val panelTexts: List<String> = emptyList(),
    val sfx: Boolean = true,
    val grain: Boolean = true,
    val progressBar: Boolean = true,
    val handle: String = "",
    val seed: Long = 1L,
    val scenes: List<Scene> = emptyList(),
    val chapters: List<String> = emptyList(),
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val lastExport: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("script", script)
        put("voicePath", voicePath); put("voiceName", voiceName)
        put("musicPath", musicPath); put("musicName", musicName); put("musicVolume", musicVolume.toDouble())
        put("images", JSONArray(images)); put("aspect", aspect.name); put("theme", themeId)
        put("pacing", pacing.name); put("captionMode", captionMode.name); put("storyMode", storyMode); put("lineImages", lineImages); put("imageStep", imageStep); put("artStyle", artStyle); put("styleDesc", styleDesc); put("characters", characters)
        put("separator", separator); put("gridSize", gridSize); put("directorPart", directorPart)
        put("visuals", JSONArray(visuals)); put("panelTexts", JSONArray(panelTexts)); put("sfx", sfx); put("grain", grain)
        put("progressBar", progressBar); put("handle", handle); put("seed", seed)
        put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.toJson()) } })
        put("chapters", JSONArray(chapters)); put("duration", durationMs)
        put("updatedAt", updatedAt); put("lastExport", lastExport)
    }

    companion object {
        fun fromJson(o: JSONObject) = Project(
            id = o.getString("id"),
            title = o.optString("title"),
            script = o.optString("script"),
            voicePath = o.optStringOrNull("voicePath"),
            voiceName = o.optStringOrNull("voiceName"),
            musicPath = o.optStringOrNull("musicPath"),
            musicName = o.optStringOrNull("musicName"),
            musicVolume = o.optDouble("musicVolume", 0.18).toFloat(),
            images = o.optJSONArray("images").strings(),
            aspect = runCatching { Aspect.valueOf(o.optString("aspect")) }.getOrDefault(Aspect.LANDSCAPE),
            themeId = o.optString("theme", "neon"),
            pacing = runCatching { Pacing.valueOf(o.optString("pacing")) }.getOrDefault(Pacing.BALANCED),
            captionMode = runCatching { CaptionMode.valueOf(o.optString("captionMode")) }.getOrDefault(CaptionMode.KEYWORDS),
            lineImages = o.optBoolean("lineImages"),
            storyMode = o.optBoolean("storyMode", true),
            imageStep = o.optInt("imageStep", 1).coerceAtLeast(1),
            artStyle = o.optString("artStyle", "storybook"),
            styleDesc = o.optString("styleDesc"),
            characters = o.optString("characters"),
            separator = o.optString("separator", "auto"),
            gridSize = o.optInt("gridSize", 9),
            directorPart = o.optInt("directorPart", 150),
            visuals = o.optJSONArray("visuals").strings(),
            panelTexts = o.optJSONArray("panelTexts").strings(),
            sfx = o.optBoolean("sfx", true),
            grain = o.optBoolean("grain", true),
            progressBar = o.optBoolean("progressBar", true),
            handle = o.optString("handle"),
            seed = o.optLong("seed", 1L),
            scenes = o.optJSONArray("scenes")?.let { a -> (0 until a.length()).map { Scene.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            chapters = o.optJSONArray("chapters").strings(),
            durationMs = o.optLong("duration"),
            updatedAt = o.optLong("updatedAt"),
            lastExport = o.optStringOrNull("lastExport"),
        )
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotEmpty() }
