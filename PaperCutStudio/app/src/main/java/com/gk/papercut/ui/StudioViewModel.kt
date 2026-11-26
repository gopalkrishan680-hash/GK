package com.gk.papercut.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gk.papercut.data.Aspect
import com.gk.papercut.data.EnterAnim
import com.gk.papercut.data.GeminiDirector
import com.gk.papercut.data.GridBatch
import com.gk.papercut.data.GridLayout
import com.gk.papercut.data.GridPlanner
import com.gk.papercut.data.IdleAnim
import com.gk.papercut.data.Layer
import com.gk.papercut.data.Project
import com.gk.papercut.data.ProjectRepository
import com.gk.papercut.data.Scene
import com.gk.papercut.data.ScriptPlanner
import com.gk.papercut.data.Settings
import com.gk.papercut.data.YoutubeKit
import com.gk.papercut.data.YoutubeKitBuilder
import com.gk.papercut.export.VideoExporter
import com.gk.papercut.ml.CutOut
import com.gk.papercut.ml.Cutter
import com.gk.papercut.ml.GridSplitter
import com.gk.papercut.ml.Upscaler
import com.gk.papercut.ml.LayerSeparator
import com.gk.papercut.ml.Mask
import com.gk.papercut.ml.MobileSam
import com.gk.papercut.ml.ModelManager
import com.gk.papercut.ml.OfflineModel
import com.gk.papercut.render.FrameRenderer
import com.gk.papercut.sync.AudioTools
import com.gk.papercut.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class Busy(val message: String, val progress: Float? = null)

class StudioViewModel(app: Application) : AndroidViewModel(app) {

    val repo = ProjectRepository(app)
    val settings = Settings(app)
    val models = ModelManager(app)
    private val separator = LayerSeparator(app, models)

    private val _projects = MutableStateFlow(repo.list())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _modelProgress = MutableStateFlow<Map<OfflineModel, Float>>(emptyMap())
    val modelProgress = _modelProgress.asStateFlow()

    /** Bumped whenever downloaded models change so the UI re-reads readiness. */
    val modelsVersion = MutableStateFlow(0)

    private val _exported = MutableStateFlow<File?>(null)
    val exported = _exported.asStateFlow()

    private var exportJob: Job? = null

    fun toastShown() { _toast.value = null }
    private fun say(msg: String) { _toast.value = msg }

    // ------------------------------------------------------------------ projects

    fun refreshProjects() { _projects.value = repo.list() }

    fun createProject(name: String, aspect: Aspect) {
        val p = repo.save(repo.create(name.ifBlank { "Untitled" }).copy(aspect = aspect))
        refreshProjects(); _project.value = p; _exported.value = null
    }

    fun open(id: String) { _project.value = repo.load(id); _exported.value = null }
    fun close() { closeSam(); _project.value = null; refreshProjects() }
    fun deleteProject(id: String) { repo.delete(id); refreshProjects() }

    fun update(transform: (Project) -> Project) {
        val p = _project.value ?: return
        _project.value = repo.save(transform(p))
    }

    private fun updateScene(sceneId: String, transform: (Scene) -> Scene) = update { p ->
        p.copy(scenes = p.scenes.map { if (it.id == sceneId) transform(it) else it })
    }

    private fun task(label: String, block: suspend () -> Unit) {
        if (_busy.value != null) return
        _busy.value = Busy(label)
        viewModelScope.launch {
            try { block() } catch (e: Throwable) {
                say("Error: ${e.message ?: e.javaClass.simpleName}")
            } finally { _busy.value = null }
        }
    }

    private fun progress(msg: String, p: Float? = null) { _busy.value = Busy(msg, p) }

    // ------------------------------------------------------------------ step 1: script + voice

    fun importAudio(uri: Uri) = task("Reading voice-over…") {
        val p = _project.value ?: return@task
        val name = "voiceover." + (displayName(uri).substringAfterLast('.', "mp3").lowercase().take(5))
        val f = withContext(Dispatchers.IO) { repo.importUri(p.id, uri, name) }
        val env = withContext(Dispatchers.Default) { AudioTools.envelope(f) }
        update { it.copy(audioFile = name, audioDuration = env.duration) }
        say("Voice-over: %.1f s".format(env.duration))
    }

    fun importSrt(uri: Uri) = task("Reading subtitles…") {
        val p = _project.value ?: return@task
        withContext(Dispatchers.IO) { repo.importUri(p.id, uri, "voiceover.srt") }
        update { it.copy(srtFile = "voiceover.srt") }
    }

    fun clearSrt() = update { it.copy(srtFile = null) }

    /** Splits the script into scenes, writes prompts and syncs them to the voice-over. */
    fun buildScenes(useAi: Boolean) = task("Planning scenes…") {
        val p = _project.value ?: return@task
        if (p.script.isBlank()) { say("Write or paste your script first"); return@task }
        var scenes = ScriptPlanner.plan(p)
        if (useAi && settings.geminiKey.isNotBlank()) {
            progress("AI Director is writing prompts…")
            scenes = runCatching {
                withContext(Dispatchers.IO) { GeminiDirector(settings.geminiKey, settings.textModel).improve(p, scenes) }
            }.getOrElse { say("AI Director failed, using offline prompts (${it.message})"); scenes }
        }
        scenes = syncScenes(p, scenes)
        update { it.copy(scenes = scenes) }
        say("${scenes.size} scenes ready")
    }

    /** Keeps scenes/images, recomputes only the timing (e.g. after changing the voice-over). */
    fun resync() = task("Syncing to voice-over…") {
        val p = _project.value ?: return@task
        val synced = syncScenes(p, p.scenes)
        update { it.copy(scenes = synced) }
        say("Timing updated")
    }

    private suspend fun syncScenes(p: Project, scenes: List<Scene>): List<Scene> = withContext(Dispatchers.Default) {
        val audio = p.audioFile?.let { repo.file(p.id, it) }?.takeIf { it.exists() }
        val srt = p.srtFile?.let { repo.file(p.id, it) }?.takeIf { it.exists() }
        when {
            srt != null -> SyncEngine.syncWithSrt(scenes, srt.readText(), p.audioDuration)
            audio != null -> { progress("Listening to voice-over pauses…"); SyncEngine.syncWithEnvelope(scenes, AudioTools.envelope(audio)) }
            else -> SyncEngine.syncByReadingSpeed(scenes)
        }
    }

    fun nudge(sceneIndex: Int, delta: Double) = update { it.copy(scenes = SyncEngine.nudgeBoundary(it.scenes, sceneIndex, delta)) }

    fun setPrompt(sceneId: String, prompt: String) = updateScene(sceneId) { it.copy(prompt = prompt) }

    // ------------------------------------------------------------------ step 2: images

    fun importImage(sceneId: String, uri: Uri) = task("Importing image…") {
        val p = _project.value ?: return@task
        withContext(Dispatchers.IO) { saveSceneImage(p, sceneId, repo.decodeScaled(uri)) }
    }

    /** Imports many images at once, sorted by file name, assigned to scenes in order. */
    fun importMany(uris: List<Uri>) = task("Importing images…") {
        val p = _project.value ?: return@task
        val sorted = uris.sortedWith(compareBy(NaturalOrder) { displayName(it) })
        val targets = p.scenes.filter { it.imageFile == null }.ifEmpty { p.scenes }
        withContext(Dispatchers.IO) {
            sorted.zip(targets).forEachIndexed { i, (uri, scene) ->
                progress("Importing ${i + 1} / ${minOf(sorted.size, targets.size)}", i / sorted.size.toFloat())
                saveSceneImage(_project.value!!, scene.id, repo.decodeScaled(uri))
            }
        }
        say("Imported ${minOf(sorted.size, targets.size)} images")
    }

    /** Pastes an image copied from ChatGPT, Gemini, Chrome, Gallery… into a scene (or the next empty one). */
    fun pasteImage(sceneId: String?) {
        val p = _project.value ?: return
        val uri = clipboardImage()
        if (uri == null) { say("No image on the clipboard – long-press the image and choose Copy"); return }
        val target = sceneId ?: p.scenes.firstOrNull { it.imageFile == null }?.id
            ?: run { say("Every scene already has an image – use Paste on a scene card to replace one"); return }
        val n = p.scenes.indexOfFirst { it.id == target } + 1
        task("Pasting image…") {
            // Decode right away: clipboard read permission is temporary.
            val bmp = withContext(Dispatchers.IO) { repo.decodeScaled(uri) }
            withContext(Dispatchers.IO) { saveSceneImage(_project.value!!, target, bmp) }
            say("Pasted into scene $n")
        }
    }

    // Grid images ---------------------------------------------------------------------------

    /** A grid image waiting for the user to check how it was cut. */
    data class PendingGrid(val batch: Int, val source: Bitmap, val cols: Int, val rows: Int, val cells: List<GridSplitter.Cell>)

    private val _pendingGrid = MutableStateFlow<PendingGrid?>(null)
    val pendingGrid: StateFlow<PendingGrid?> = _pendingGrid.asStateFlow()

    fun setGrid(g: GridLayout) = update { it.copy(grid = g) }

    fun batches(): List<GridBatch> = _project.value?.let { GridPlanner.batches(it) }.orEmpty()

    private fun nextOpenBatch(): Int? = batches().firstOrNull { b -> b.scenes.any { it.imageFile == null } }?.index

    /** Last grid image per batch, kept in memory so "Fix cut" can re-cut it with another layout. */
    private val gridSources = HashMap<Int, Bitmap>()
    private val _gridSourcesVersion = MutableStateFlow(0)
    val gridSourcesVersion = _gridSourcesVersion.asStateFlow()
    fun hasGridSource(batch: Int) = gridSources.containsKey(batch)

    /** Cuts the grid and fills the scenes straight away, in panel order (left→right, top→bottom). */
    fun importGrid(batch: Int, uri: Uri) = task("Cutting grid…") {
        val bmp = withContext(Dispatchers.IO) { repo.decodeScaled(uri, 4096) }
        autoGrid(batch, bmp)
    }

    fun pasteGrid(batch: Int?) {
        val uri = clipboardImage() ?: run { say("No image on the clipboard – long-press the image and choose Copy"); return }
        val b = batch ?: nextOpenBatch() ?: run { say("Every grid already has images – use Paste on a grid card to replace one"); return }
        task("Cutting grid…") {
            val bmp = withContext(Dispatchers.IO) { repo.decodeScaled(uri, 4096) }
            autoGrid(b, bmp)
        }
    }

    private suspend fun autoGrid(batch: Int, bmp: Bitmap) {
        val b = batches().getOrNull(batch) ?: return
        gridSources[batch] = bmp; _gridSourcesVersion.update { it + 1 }
        val cells = withContext(Dispatchers.Default) { GridSplitter.split(bmp, b.cols, b.rows) }
        assignGrid(batch, cells)
    }

    /** Opens the cut preview for a grid that was already imported (only needed if the AI drew another layout). */
    fun fixGridCut(batch: Int) {
        val src = gridSources[batch] ?: return
        val b = batches().getOrNull(batch) ?: return
        task("Cutting grid…") { prepareGrid(batch, src, b.cols, b.rows) }
    }

    /** Re-cut the waiting grid with a different layout (when the AI drew e.g. 2x3 instead of 3x2). */
    fun resplitGrid(cols: Int, rows: Int) {
        val pg = _pendingGrid.value ?: return
        task("Cutting grid…") { prepareGrid(pg.batch, pg.source, cols, rows) }
    }

    private suspend fun prepareGrid(batch: Int, bmp: Bitmap, cols: Int?, rows: Int?) {
        val b = batches().getOrNull(batch) ?: return
        val c = cols ?: b.cols; val r = rows ?: b.rows
        val cells = withContext(Dispatchers.Default) { GridSplitter.split(bmp, c, r) }
        _pendingGrid.value = PendingGrid(batch, bmp, c, r, cells)
    }

    fun cancelGrid() { _pendingGrid.value = null }

    fun confirmGrid() {
        val pg = _pendingGrid.value ?: return
        _pendingGrid.value = null
        task("Saving panels…") { assignGrid(pg.batch, pg.cells) }
    }

    /** Panel i goes to scene i of the batch; empty panels and extra panels are skipped. */
    private suspend fun assignGrid(batch: Int, cells: List<GridSplitter.Cell>) {
        val b = batches().getOrNull(batch) ?: return
        var filled = 0
        withContext(Dispatchers.IO) {
            b.scenes.forEachIndexed { i, scene ->
                val bmp = cells.getOrNull(i)?.bitmap ?: return@forEachIndexed
                saveSceneImage(_project.value!!, scene.id, scaleDown(bmp, 1600)); filled++
            }
        }
        val missing = b.scenes.size - filled
        say("Grid ${batch + 1}: $filled scenes filled" + if (missing > 0) ", $missing panels empty – add those scenes one by one" else "")
    }

    /** Several grid images at once (sorted by name), each auto-cut into the next open grid. */
    fun importGridMany(uris: List<Uri>) = task("Cutting grids…") {
        val sorted = uris.sortedWith(compareBy(NaturalOrder) { displayName(it) })
        var batch = nextOpenBatch() ?: 0
        sorted.forEachIndexed { i, uri ->
            val b = batches().getOrNull(batch) ?: return@forEachIndexed
            progress("Cutting grid ${i + 1} / ${sorted.size}", i / sorted.size.toFloat())
            val bmp = withContext(Dispatchers.IO) { repo.decodeScaled(uri, 4096) }
            autoGrid(b.index, bmp)
            batch++
        }
    }

    private fun scaleDown(b: Bitmap, max: Int): Bitmap {
        val l = maxOf(b.width, b.height)
        if (l <= max) return b
        val k = max.toFloat() / l
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    private fun clipboardImage(): Uri? {
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip ?: return null
        return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).uri }
    }

    /** Images shared to the app from another app ("Share → PaperCut Studio"). */
    fun importShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_project.value == null) {
            val recent = repo.list().firstOrNull() ?: run { say("Create a project first, then share the images again"); return }
            open(recent.id)
        }
        if (_project.value!!.scenes.isEmpty()) { say("Create scenes in step 1 first, then share the images again"); return }
        if (_project.value!!.grid != GridLayout.SINGLE) importGridMany(uris) else importMany(uris)
    }

    private fun saveSceneImage(p: Project, sceneId: String, original: Bitmap) {
        // Small pictures (grid panels) are sharpened with the offline AI upscaler when it is downloaded.
        val bmp = if (models.upscalerReady && Upscaler.needsUpscale(original))
            runCatching { Upscaler.upscale(original, models.file(OfflineModel.UPSCALER)) }.getOrDefault(original) else original
        val name = repo.saveBitmap(p.id, "s_${sceneId}_img_${System.currentTimeMillis() % 100000}.png", bmp)
        updateScene(sceneId) { it.copy(imageFile = name, backgroundFile = null, layers = emptyList(), imageW = bmp.width, imageH = bmp.height) }
        if (samSceneId == sceneId) closeSam()
    }

    /** Sharpens every small scene image already imported, then separates the scenes again. */
    fun upscaleAll(useSam: Boolean) = task("AI upscaling…") {
        if (!models.upscalerReady) { say("Download the AI upscaler first (3 MB)"); return@task }
        val p = _project.value ?: return@task
        val todo = p.scenes.filter { it.imageFile != null && maxOf(it.imageW, it.imageH) in 1 until 1400 }
        if (todo.isEmpty()) { say("All images are already sharp enough"); return@task }
        todo.forEachIndexed { i, sc ->
            withContext(Dispatchers.Default) {
                val img = repo.loadBitmap(p.id, sc.imageFile) ?: return@withContext
                val up = Upscaler.upscale(img, models.file(OfflineModel.UPSCALER)) { f ->
                    progress("Upscaling scene ${p.scenes.indexOf(sc) + 1} (${i + 1}/${todo.size})", (i + f) / todo.size)
                }
                saveSceneImage(_project.value!!, sc.id, up)
            }
        }
        val again = _project.value!!.scenes.filter { it.imageFile != null }
        again.forEachIndexed { i, s ->
            separateScene(s.id, useSam) { msg -> progress("Separating scene ${i + 1}/${again.size}: $msg", i / again.size.toFloat()) }
        }
        say("${todo.size} images sharpened and separated again")
    }

    // ------------------------------------------------------------------ step 3: separation

    fun separateAll(useSam: Boolean) = task("Separating elements…") {
        val todo = _project.value?.scenes?.filter { it.imageFile != null } ?: return@task
        todo.forEachIndexed { i, s ->
            separateScene(s.id, useSam) { msg -> progress("Scene ${i + 1}/${todo.size}: $msg", i / todo.size.toFloat()) }
        }
        say("All scenes separated")
    }

    fun separate(sceneId: String, useSam: Boolean) = task("Separating elements…") {
        separateScene(sceneId, useSam) { progress(it) }
    }

    private suspend fun separateScene(sceneId: String, useSam: Boolean, msg: (String) -> Unit) {
        val p = _project.value ?: return
        val scene = p.scenes.first { it.id == sceneId }
        val img = withContext(Dispatchers.IO) { repo.loadBitmap(p.id, scene.imageFile) } ?: return
        val res = withContext(Dispatchers.Default) { separator.separate(img, useSam, msg) }
        val layers = withContext(Dispatchers.IO) { buildLayers(p, scene, res.cutOuts, img.width, img.height) }
        val bgName = repo.saveBitmap(p.id, "s_${sceneId}_bg_${System.currentTimeMillis() % 100000}.png", res.background)
        updateScene(sceneId) { it.copy(backgroundFile = bgName, layers = layers) }
        if (layers.isEmpty()) say("No separate elements found – the scene will animate as one picture. Try tap-to-cut.")
    }

    /**
     * Animation plan for a freshly separated scene:
     *  - set pieces (pillars, floors) and the crowd are on stage from the first frame, so the
     *    repainted background behind them is never shown;
     *  - up to 3 "heroes" (elements the script mentions, else the biggest) make an entrance right at
     *    the scene start and react (hop) when their word is spoken.
     */
    private fun buildLayers(p: Project, scene: Scene, cuts: List<CutOut>, w: Int, h: Int): List<Layer> {
        val hints = scene.elementsHint.sortedBy { if (it.wordIndex < 0) Int.MAX_VALUE else it.wordIndex }
        val byX = cuts.sortedBy { it.x + it.bitmap.width / 2 }
        val actors = byX.filter { !isSetPiece(it, w, h) }
        val heroCount = minOf(3, maxOf(hints.size, 1), actors.size)
        val heroes = actors.sortedByDescending { it.bitmap.width * it.bitmap.height }.take(heroCount).toSet()
        var heroIdx = 0
        val layers = byX.mapIndexed { i, c ->
            val name = repo.saveBitmap(p.id, "s_${scene.id}_l${i}_${UUID.randomUUID().toString().take(4)}.png", c.bitmap)
            val hero = c in heroes
            val hint = if (hero) hints.getOrNull(heroIdx++) else null
            Layer(
                id = UUID.randomUUID().toString().take(8), file = name,
                x = c.x.toFloat(), y = c.y.toFloat(), w = c.bitmap.width.toFloat(), h = c.bitmap.height.toFloat(),
                label = hint?.name ?: if (hero) "Hero ${heroIdx}" else if (isSetPiece(c, w, h)) "Set piece ${i + 1}" else "Crowd ${i + 1}",
                role = if (hero) "hero" else if (isSetPiece(c, w, h)) "set" else "crowd",
                enter = if (hero) hint?.enter ?: defaultEnter(c, w, h) else EnterAnim.NONE,
                idle = if (hero) hint?.idle ?: IdleAnim.BOB else IdleAnim.NONE,
                keywordIndex = hint?.wordIndex ?: -1,
            )
        }
        // Big pieces go underneath, small ones on top.
        return layers.sortedByDescending { it.w * it.h }
    }

    /** Pillars hanging from the top edge and wide floors on the bottom edge stay in place (they still get parallax). */
    private fun isSetPiece(c: CutOut, w: Int, h: Int): Boolean {
        val m = 4
        val top = c.y <= m
        val bottomWide = c.y + c.bitmap.height >= h - m && c.bitmap.width > w * 0.3f
        return top || bottomWide
    }

    private fun defaultEnter(c: CutOut, w: Int, h: Int): EnterAnim {
        val cx = c.x + c.bitmap.width / 2f; val cy = c.y + c.bitmap.height / 2f
        return when {
            c.bitmap.height > h * 0.5f && cy > h * 0.45f -> EnterAnim.GROW_UP
            cy < h * 0.3f -> EnterAnim.DROP
            cx < w * 0.35f -> EnterAnim.SLIDE_LEFT
            cx > w * 0.65f -> EnterAnim.SLIDE_RIGHT
            else -> EnterAnim.POP
        }
    }

    // Tap-to-cut with MobileSAM ---------------------------------------------------------------

    private var sam: MobileSam? = null
    private var samSceneId: String? = null

    private fun closeSam() { sam?.close(); sam = null; samSceneId = null }

    fun tapToCut(sceneId: String, x: Float, y: Float) = task("Cutting element (MobileSAM)…") {
        if (!models.samReady) { say("Download MobileSAM in Settings to use tap-to-cut"); return@task }
        val p = _project.value ?: return@task
        val scene = p.scenes.first { it.id == sceneId }
        val img = withContext(Dispatchers.IO) { repo.loadBitmap(p.id, scene.imageFile) } ?: return@task
        withContext(Dispatchers.Default) {
            if (samSceneId != sceneId) {
                closeSam()
                progress("Reading image (MobileSAM)…")
                sam = MobileSam(models.file(OfflineModel.SAM_ENCODER), models.file(OfflineModel.SAM_DECODER)).also { it.setImage(img) }
                samSceneId = sceneId
            }
            val mask = sam!!.segment(x, y)
            val cut = mask?.let { Cutter.cut(img, it) } ?: run { say("Nothing found there"); return@withContext }
            val name = repo.saveBitmap(p.id, "s_${sceneId}_t_${UUID.randomUUID().toString().take(6)}.png", cut.bitmap)
            val layer = Layer(
                id = UUID.randomUUID().toString().take(8), file = name,
                x = cut.x.toFloat(), y = cut.y.toFloat(), w = cut.bitmap.width.toFloat(), h = cut.bitmap.height.toFloat(),
                label = "Element ${scene.layers.size + 1}", enter = defaultEnter(cut, img.width, img.height),
            )
            val layers = scene.layers + layer
            progress("Repainting background…")
            val bg = rebuildBackground(p, img, layers)
            updateScene(sceneId) { it.copy(layers = layers, backgroundFile = bg) }
        }
    }

    private fun rebuildBackground(p: Project, img: Bitmap, layers: List<Layer>): String {
        val masks = layers.mapNotNull { l ->
            repo.loadBitmap(p.id, l.file)?.let { Mask.fromAlpha(img.width, img.height, it, l.x.toInt(), l.y.toInt()) }
        }
        val bg = separator.background(img, masks)
        return repo.saveBitmap(p.id, "s_bg_${UUID.randomUUID().toString().take(6)}.png", bg)
    }

    fun updateLayer(sceneId: String, layerId: String, transform: (Layer) -> Layer) =
        updateScene(sceneId) { s -> s.copy(layers = s.layers.map { if (it.id == layerId) transform(it) else it }) }

    fun moveLayer(sceneId: String, layerId: String, up: Boolean) = updateScene(sceneId) { s ->
        val l = s.layers.toMutableList(); val i = l.indexOfFirst { it.id == layerId }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in l.indices) s else s.copy(layers = l.also { java.util.Collections.swap(it, i, j) })
    }

    /** Deleting a layer puts it back into the background picture. */
    fun deleteLayer(sceneId: String, layerId: String) = task("Updating background…") {
        val p = _project.value ?: return@task
        val scene = p.scenes.first { it.id == sceneId }
        val layers = scene.layers.filter { it.id != layerId }
        val img = withContext(Dispatchers.IO) { repo.loadBitmap(p.id, scene.imageFile) } ?: return@task
        val bg = withContext(Dispatchers.Default) { rebuildBackground(p, img, layers) }
        updateScene(sceneId) { it.copy(layers = layers, backgroundFile = bg) }
    }

    fun setScene(sceneId: String, transform: (Scene) -> Scene) = updateScene(sceneId, transform)

    // ------------------------------------------------------------------ step 5: YouTube kit

    fun setYoutube(k: YoutubeKit) = update { it.copy(youtube = k) }

    fun generateYoutube() = task("Writing your YouTube kit…") {
        val p = _project.value ?: return@task
        if (settings.geminiKey.isBlank()) { say("Add a Gemini key in Settings, or use “Copy ChatGPT prompt”"); return@task }
        val kit = withContext(Dispatchers.IO) { YoutubeKitBuilder.generateWithGemini(p, GeminiDirector(settings.geminiKey, settings.textModel)) }
        setYoutube(kit); say("YouTube kit ready")
    }

    /** Reads ChatGPT's answer from the clipboard and fills the kit (hashtags fixed to a small first letter). */
    fun pasteYoutubeAnswer() {
        val p = _project.value ?: return
        val cm = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString().orEmpty()
        if (text.isBlank()) { say("Copy ChatGPT's whole answer first"); return }
        val kit = YoutubeKitBuilder.parseChatbotAnswer(text, p)
        setYoutube(kit)
        say("Filled: ${kit.titles.size} titles, ${kit.hashtags.size} hashtags, ${kit.tags.size} tags")
    }

    fun offlineChapters() = update { it.copy(youtube = it.youtube.copy(chapters = YoutubeKitBuilder.chapters(it, null))) }

    // ------------------------------------------------------------------ step 4: export

    fun export() {
        val p = _project.value ?: return
        if (p.scenes.isEmpty()) { say("Build scenes first"); return }
        _busy.value = Busy("Preparing export…", 0f)
        exportJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val out = repo.file(p.id, "${p.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.mp4")
                val renderer = FrameRenderer(p, repo)
                VideoExporter(renderer, p.audioFile?.let { repo.file(p.id, it) }).export(out) { f, m -> _busy.value = Busy(m, f) }
                _exported.value = out
                say("Video ready! Save it to the gallery or share it.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                say("Export cancelled")
            } catch (e: Throwable) {
                say("Export failed: ${e.message}")
            } finally { _busy.value = null }
        }
    }

    fun cancelExport() { exportJob?.cancel() }

    fun saveToGallery(f: File) = task("Saving to gallery…") {
        withContext(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PaperCutStudio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Gallery not available")
            cr.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        }
        say("Saved to Movies/PaperCutStudio")
    }

    fun shareIntent(f: File): Intent {
        val ctx = getApplication<Application>()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        return Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ------------------------------------------------------------------ models

    fun downloadModel(m: OfflineModel) {
        if (_modelProgress.value.containsKey(m)) return
        _modelProgress.update { it + (m to 0f) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                models.download(m) { f -> _modelProgress.update { it + (m to f) } }
                say("${m.label} downloaded")
            } catch (e: Throwable) {
                say("Download failed: ${e.message} – tap again to resume")
            } finally {
                _modelProgress.update { it - m }; modelsVersion.update { it + 1 }
            }
        }
    }

    fun deleteModel(m: OfflineModel) { models.delete(m); closeSam(); modelsVersion.update { it + 1 } }

    // ------------------------------------------------------------------ helpers

    fun displayName(uri: Uri): String {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return uri.lastPathSegment ?: ""
    }

    override fun onCleared() { closeSam() }
}

/** "scene_2" before "scene_10". */
object NaturalOrder : Comparator<String> {
    private val re = Regex("(\\d+)|(\\D+)")
    override fun compare(a: String, b: String): Int {
        val x = re.findAll(a.lowercase()).map { it.value }.toList()
        val y = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.toBigInteger().compareTo(q.toBigInteger()) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
