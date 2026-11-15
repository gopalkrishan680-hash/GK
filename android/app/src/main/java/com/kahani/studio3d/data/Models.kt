package com.kahani.studio3d.data

import kotlinx.serialization.Serializable

@Serializable
enum class AspectRatio(val label: String, val w: Int, val h: Int) {
    PORTRAIT("9:16 Shorts/Reels", 9, 16),
    LANDSCAPE("16:9 YouTube", 16, 9),
    SQUARE("1:1 Post", 1, 1),
    FOUR_FIVE("4:5 Feed", 4, 5);

    /** Output size for a given "p" quality (shorter side). Always even numbers. */
    fun size(shortSide: Int): Pair<Int, Int> {
        return if (w >= h) {
            val hh = shortSide
            val ww = (shortSide * w.toFloat() / h).toInt()
            (ww and 1.inv()) to (hh and 1.inv())
        } else {
            val ww = shortSide
            val hh = (shortSide * h.toFloat() / w).toInt()
            (ww and 1.inv()) to (hh and 1.inv())
        }
    }
}

@Serializable
enum class VisualStyle(val label: String, val promptPrefix: String, val promptSuffix: String) {
    PIXAR_3D(
        "Pixar 3D Cartoon",
        "3D animated cartoon render in Pixar/Disney style",
        "soft global illumination, expressive big eyes, smooth stylized shapes, vibrant colors, subsurface scattering, highly detailed, octane render, 8k"
    ),
    REALISTIC_3D(
        "Cinematic Realistic 3D",
        "Hyper-realistic cinematic 3D render",
        "dramatic volumetric lighting, shallow depth of field, ray tracing, photorealistic textures, film still, unreal engine 5, 8k"
    ),
    CLAY_3D(
        "Clay / Claymation",
        "Cute claymation 3D scene, handmade plasticine style",
        "soft studio lighting, tactile clay texture, fingerprint details, miniature diorama, pastel colors, tilt-shift"
    ),
    ANIME_3D(
        "Anime 3D",
        "3D anime style render, cel-shaded",
        "vivid colors, dynamic composition, clean outlines, studio quality anime lighting, detailed background"
    ),
    MINIATURE(
        "Miniature Diorama",
        "Tiny isometric 3D miniature diorama",
        "tilt-shift photography, cute tiny details, soft shadows, warm lighting, blender render"
    ),
    LOW_POLY(
        "Low Poly",
        "Low poly 3D illustration",
        "geometric facets, minimal clean colors, soft gradient sky, isometric composition"
    ),
    MYTHOLOGY(
        "Indian Mythology 3D",
        "Epic 3D render of Indian mythological scene",
        "divine golden glow, ornate traditional jewellery and costumes, majestic temple architecture, cinematic god rays, highly detailed, 8k"
    ),
    HORROR(
        "Dark Horror 3D",
        "Dark eerie cinematic 3D render",
        "low-key lighting, fog, desaturated cold tones, ominous shadows, suspenseful atmosphere, highly detailed"
    );
}

@Serializable
enum class Motion(val label: String) {
    AUTO("Auto (mix)"),
    ZOOM_IN("Zoom In"),
    ZOOM_OUT("Zoom Out"),
    PAN_LEFT("Pan Left"),
    PAN_RIGHT("Pan Right"),
    PAN_UP("Pan Up"),
    PAN_DOWN("Pan Down"),
    TILT_3D("3D Tilt"),
    ORBIT_3D("3D Orbit"),
    DOLLY_ROTATE("Dolly + Rotate"),
    SHAKE("Handheld Shake"),
    STATIC("Static");

    companion object {
        val autoCycle = listOf(ZOOM_IN, TILT_3D, PAN_RIGHT, ZOOM_OUT, ORBIT_3D, PAN_LEFT, DOLLY_ROTATE, PAN_UP)
    }
}

@Serializable
enum class Transition(val label: String) {
    CROSSFADE("Crossfade"),
    SLIDE("Slide"),
    ZOOM_THROUGH("Zoom Through"),
    FLASH("Flash White"),
    DIP_BLACK("Dip to Black"),
    CUBE_3D("3D Cube Flip"),
    NONE("Cut");
}

@Serializable
enum class CaptionStyle(val label: String) {
    KARAOKE_YELLOW("Karaoke (word highlight)"),
    BOLD_BOX("Bold Box"),
    CLASSIC("Classic Subtitle"),
    POP_WORD("Pop Single Word"),
    NONE("No Captions");
}

@Serializable
enum class ColorFilter(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    CINEMATIC("Teal & Orange"),
    VINTAGE("Vintage"),
    BW("Black & White");
}

@Serializable
enum class CaptionPosition(val label: String) { BOTTOM("Bottom"), CENTER("Center"), TOP("Top") }

@Serializable
enum class SplitMode(val label: String) {
    SENTENCE("1 sentence = 1 scene"),
    LINE("1 line = 1 scene"),
    SMART("Smart (~8 sec per scene)");
}

@Serializable
enum class ShotType(val label: String) {
    WIDE("wide establishing shot"),
    MEDIUM("medium shot"),
    CLOSE("close-up shot"),
    LOW_ANGLE("low angle heroic shot"),
    OVER_SHOULDER("over-the-shoulder shot"),
    TOP_DOWN("top-down aerial shot"),
}

@Serializable
data class Character(
    val name: String,
    val description: String,
)

@Serializable
data class Scene(
    val id: String,
    val text: String,
    val prompt: String = "",
    val imagePath: String? = null,
    val durationMs: Long = 4000,
    val motion: Motion = Motion.AUTO,
    val transition: Transition? = null, // null = use project default
    val locked: Boolean = false, // duration manually set
)

@Serializable
data class Project(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val script: String = "",
    val voicePath: String? = null,
    val voiceName: String? = null,
    val voiceDurationMs: Long = 0,
    val musicPath: String? = null,
    val musicName: String? = null,
    val musicVolume: Float = 0.18f,
    val duckMusic: Boolean = true,
    val aspect: AspectRatio = AspectRatio.PORTRAIT,
    val style: VisualStyle = VisualStyle.PIXAR_3D,
    val extraPrompt: String = "",
    val negativePrompt: String = "blurry, low quality, text, watermark, deformed hands, extra fingers",
    val characters: List<Character> = emptyList(),
    val splitMode: SplitMode = SplitMode.SENTENCE,
    val scenes: List<Scene> = emptyList(),
    val transition: Transition = Transition.CROSSFADE,
    val transitionMs: Long = 450,
    val captionStyle: CaptionStyle = CaptionStyle.KARAOKE_YELLOW,
    val captionPosition: CaptionPosition = CaptionPosition.BOTTOM,
    val captionSize: Float = 1f,
    val colorFilter: ColorFilter = ColorFilter.NONE,
    val vignette: Boolean = true,
    val letterbox: Boolean = false,
    val filmGrain: Boolean = false,
    val watermark: String = "",
    val motionIntensity: Float = 1f,
) {
    val imagesDone: Int get() = scenes.count { it.imagePath != null }
    val totalDurationMs: Long get() = scenes.sumOf { it.durationMs }
    val thumbnail: String? get() = scenes.firstOrNull { it.imagePath != null }?.imagePath
}
