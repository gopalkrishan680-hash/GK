package com.gk.papercut.data

import kotlinx.serialization.Serializable

@Serializable
enum class Aspect(val label: String, val w: Int, val h: Int, val ratio: String) {
    LANDSCAPE("16:9 YouTube", 1280, 720, "16:9"),
    PORTRAIT("9:16 Shorts / Reels", 720, 1280, "9:16"),
    SQUARE("1:1 Square", 960, 960, "1:1"),
}

/** How a cut-out element arrives on stage. */
@Serializable
enum class EnterAnim(val label: String) {
    POP("Pop"), SLIDE_LEFT("Slide from left"), SLIDE_RIGHT("Slide from right"),
    SLIDE_UP("Rise from bottom"), DROP("Drop from top"), GROW_UP("Grow up"),
    SPIN_IN("Spin in"), FLIP("Paper flip"), FADE("Fade"), WOBBLE_IN("Wobble in"), NONE("Already there"),
}

/** Looping motion while the element is on stage. */
@Serializable
enum class IdleAnim(val label: String) {
    NONE("Still"), BOB("Bob"), SWAY("Sway"), PULSE("Pulse"), SHAKE("Shake"),
    FLOAT("Float"), WALK("Walk"), SPIN("Spin"),
}

@Serializable
enum class CameraMove(val label: String) {
    ZOOM_IN("Slow zoom in"), ZOOM_OUT("Slow zoom out"), PAN_LEFT("Pan left"),
    PAN_RIGHT("Pan right"), STATIC("Static"),
}

@Serializable
enum class Transition(val label: String) {
    PAPER_SLIDE("Paper slide"), PAPER_FLIP("Paper flip"), FOLD("Fold"), ZOOM("Zoom through"), CUT("Hard cut"),
}

/** A separated cut-out element. Coordinates are in the scene image's pixel space. */
@Serializable
data class Layer(
    val id: String,
    val file: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val label: String = "Element",
    val enter: EnterAnim = EnterAnim.POP,
    val idle: IdleAnim = IdleAnim.BOB,
    /** Word index (within the scene) that triggers the entrance, or -1 for automatic stagger. */
    val keywordIndex: Int = -1,
    val visible: Boolean = true,
    /** "hero", "crowd" or "set" (pillars/floors). Empty for layers from older versions. */
    val role: String = "",
)

/** An element the script mentions; used for the prompt and to time the matching cut-out. */
@Serializable
data class ElementHint(
    val name: String,
    val wordIndex: Int = -1,
    val enter: EnterAnim = EnterAnim.POP,
    val idle: IdleAnim = IdleAnim.BOB,
)

@Serializable
data class WordTime(val text: String, val start: Double, val end: Double)

@Serializable
data class Scene(
    val id: String,
    val text: String,
    val prompt: String = "",
    val elementsHint: List<ElementHint> = emptyList(),
    val start: Double = 0.0,
    val end: Double = 0.0,
    val words: List<WordTime> = emptyList(),
    /** Original image the user imported (scaled). */
    val imageFile: String? = null,
    /** Clean background plate after the elements were cut out and the holes were in-painted. */
    val backgroundFile: String? = null,
    val imageW: Int = 0,
    val imageH: Int = 0,
    val layers: List<Layer> = emptyList(),
    val camera: CameraMove = CameraMove.ZOOM_IN,
    val transition: Transition = Transition.PAPER_SLIDE,
    /** Keyword pop-up: -2 = automatic, -1 = none, otherwise the word index in this scene. */
    val popWord: Int = -2,
) {
    val duration get() = (end - start).coerceAtLeast(0.0)
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val script: String = "",
    val audioFile: String? = null,
    val srtFile: String? = null,
    val audioDuration: Double = 0.0,
    val aspect: Aspect = Aspect.LANDSCAPE,
    val styleNote: String = "",
    val characterNote: String = "",
    val scenes: List<Scene> = emptyList(),
    val captions: Boolean = true,
    val stopMotion: Boolean = true,
    val paperTexture: Boolean = true,
    /** Elements build the scene one after another (true) or are all on stage from the start (false). */
    val buildUp: Boolean = true,
    /** Camera zooms/pans and close-up shots. Off = steady full-frame picture. */
    val cameraMoves: Boolean = true,
    /** Background slightly blurred and dimmed so the cut-outs stand out (2.5D depth). */
    val depthEffect: Boolean = true,
    /** Big paper-cut letters for one important word per scene. */
    val keywordPopups: Boolean = true,
    /** Export height: 720 or 1080 (landscape), width for portrait. */
    val resolution: Int = 720,
    val grid: GridLayout = GridLayout.SINGLE,
    val youtube: YoutubeKit = YoutubeKit(),
    val updatedAt: Long = System.currentTimeMillis(),
)
