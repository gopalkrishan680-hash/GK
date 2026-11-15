package com.kahani.studio3d

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kahani.studio3d.data.Character
import com.kahani.studio3d.data.Project
import com.kahani.studio3d.data.ProjectRepository
import com.kahani.studio3d.data.Prefs
import com.kahani.studio3d.data.Scene
import com.kahani.studio3d.logic.AudioTools
import com.kahani.studio3d.logic.PromptGenerator
import com.kahani.studio3d.logic.SceneLogic
import com.kahani.studio3d.render.ExportCancelled
import com.kahani.studio3d.render.VideoExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Busy(val label: String, val progress: Float? = null)

data class ExportState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val label: String = "",
    val file: File? = null,
    val galleryUri: Uri? = null,
    val error: String? = null,
)

class StudioViewModel(app: Application) : AndroidViewModel(app) {
    val repo = ProjectRepository(app)
    val prefs = Prefs(app)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _current = MutableStateFlow<Project?>(null)
    val current: StateFlow<Project?> = _current.asStateFlow()

    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()

    private val _export = MutableStateFlow(ExportState())
    val export: StateFlow<ExportState> = _export.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Pause positions (ms) found in the current voiceover — used for auto sync. */
    private val pauses = HashMap<String, List<Long>>()
    private var saveJob: Job? = null
    private var exporter: VideoExporter? = null

    init { refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) { _projects.value = repo.list() }

    fun toast(s: String) { messages.tryEmit(s) }

    // ------------------------------------------------------------ project CRUD

    fun newProject(title: String): String {
        val p = repo.create(title.ifBlank { "Meri Video" }).copy(
            style = prefs.defaultStyle, aspect = prefs.defaultAspect, watermark = prefs.defaultWatermark
        )
        repo.save(p)
        _current.value = p
        refresh()
        return p.id
    }

    fun open(id: String) {
        if (_current.value?.id == id) return
        _current.value = repo.load(id)
        _export.value = ExportState()
        _current.value?.let { p -> if (p.voicePath != null && pauses[p.id] == null) analyzeVoice(p, retime = false) }
    }

    fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) {
        repo.delete(id); if (_current.value?.id == id) _current.value = null; refresh()
    }

    fun duplicate(p: Project) = viewModelScope.launch(Dispatchers.IO) { repo.duplicate(p); refresh() }

    /** Every edit goes through here; saving is debounced. */
    fun update(transform: (Project) -> Project) {
        val p = _current.value ?: return
        val np = transform(p).copy(updatedAt = System.currentTimeMillis())
        _current.value = np
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400); repo.save(np); _projects.value = repo.list()
        }
    }

    fun updateScene(index: Int, transform: (Scene) -> Scene) = update { p ->
        p.copy(scenes = p.scenes.mapIndexed { i, s -> if (i == index) transform(s) else s })
    }

    // ------------------------------------------------------------ script & scenes

    fun applyScript(text: String) {
        update { p ->
            val texts = SceneLogic.split(text, p.splitMode)
            val scenes = SceneLogic.buildScenes(texts, p.scenes)
            val np = p.copy(script = text, scenes = scenes)
            np.copy(scenes = SceneLogic.autoTime(np, pauses[p.id].orEmpty()))
        }
    }

    fun resync() = update { p -> p.copy(scenes = SceneLogic.autoTime(p.copy(scenes = p.scenes.map { it.copy(locked = false) }), pauses[p.id].orEmpty())) }

    fun retime() = update { p -> p.copy(scenes = SceneLogic.autoTime(p, pauses[p.id].orEmpty())) }

    fun mergeWithNext(i: Int) = update { p ->
        if (i >= p.scenes.lastIndex) return@update p
        val a = p.scenes[i]; val b = p.scenes[i + 1]
        val merged = a.copy(text = a.text + " " + b.text, durationMs = a.durationMs + b.durationMs, prompt = "", imagePath = a.imagePath ?: b.imagePath)
        p.copy(scenes = p.scenes.toMutableList().apply { set(i, merged); removeAt(i + 1) })
    }

    fun splitScene(i: Int) = update { p ->
        val s = p.scenes[i]
        val words = s.text.split(Regex("\\s+"))
        if (words.size < 2) return@update p
        val half = words.size / 2
        val a = s.copy(text = words.take(half).joinToString(" "), durationMs = s.durationMs / 2, prompt = "")
        val b = Scene(id = java.util.UUID.randomUUID().toString().take(8), text = words.drop(half).joinToString(" "), durationMs = s.durationMs - s.durationMs / 2)
        p.copy(scenes = p.scenes.toMutableList().apply { set(i, a); add(i + 1, b) })
    }

    fun moveScene(i: Int, dir: Int) = update { p ->
        val j = i + dir
        if (j !in p.scenes.indices) return@update p
        p.copy(scenes = p.scenes.toMutableList().apply { val t = this[i]; this[i] = this[j]; this[j] = t })
    }

    fun deleteScene(i: Int) = update { p -> p.copy(scenes = p.scenes.toMutableList().apply { removeAt(i) }) }

    fun setCharacters(list: List<Character>) = update { it.copy(characters = list) }

    // ------------------------------------------------------------ audio

    fun importVoice(uri: Uri) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        _busy.value = Busy("Voiceover import ho raha hai…")
        try {
            val (path, name, dur) = withContext(Dispatchers.IO) {
                val path = repo.importFile(p.id, uri, "voice")
                Triple(path, repo.displayName(uri), AudioTools.durationMs(path))
            }
            if (dur <= 0) { toast("Ye audio file read nahi ho payi. MP3/M4A/WAV try karein."); return@launch }
            update { it.copy(voicePath = path, voiceName = name, voiceDurationMs = dur) }
            analyzeVoice(_current.value!!, retime = true)
        } catch (e: Exception) {
            toast("Voiceover error: ${e.message}")
        } finally { _busy.value = null }
    }

    /** Decodes the voiceover once to find breathing pauses, then syncs scene cuts to them. */
    private fun analyzeVoice(p: Project, retime: Boolean) = viewModelScope.launch {
        val path = p.voicePath ?: return@launch
        _busy.value = Busy("Voice analyse ho rahi hai (auto-sync)…", 0f)
        try {
            val found = withContext(Dispatchers.Default) {
                val pcm = File(getApplication<Application>().cacheDir, "analyze.pcm")
                AudioTools.decodeToPcm(path, pcm) { _busy.value = Busy("Voice analyse ho rahi hai (auto-sync)…", it) }
                val env = AudioTools.envelope(pcm)
                pcm.delete()
                AudioTools.findPauses(env)
            }
            pauses[p.id] = found
            if (retime) { retime(); toast("Scenes voice ke pauses par auto-sync ho gaye (${found.size} pauses mile)") }
        } catch (e: Exception) {
            if (retime) retime()
        } finally { _busy.value = null }
    }

    fun importMusic(uri: Uri) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        val (path, name) = withContext(Dispatchers.IO) { repo.importFile(p.id, uri, "music") to repo.displayName(uri) }
        update { it.copy(musicPath = path, musicName = name) }
    }

    fun removeMusic() = update { it.copy(musicPath = null, musicName = null) }

    // ------------------------------------------------------------ prompts

    fun generatePrompts(useAi: Boolean, overwrite: Boolean) = viewModelScope.launch {
        val p0 = _current.value ?: return@launch
        if (p0.scenes.isEmpty()) { toast("Pehle script likhiye"); return@launch }
        val p = if (overwrite) p0.copy(scenes = p0.scenes.map { it.copy(prompt = "") }) else p0
        val key = prefs.geminiKey
        if (useAi && key.isBlank()) { toast("AI prompts ke liye Settings me free Gemini API key daaliye"); return@launch }
        _busy.value = Busy(if (useAi) "AI prompts likh raha hai…" else "Prompts ban rahe hain…", 0f)
        try {
            val scenes = withContext(Dispatchers.IO) {
                if (useAi) {
                    val todo = p.copy(scenes = p.scenes)
                    val ai = PromptGenerator.gemini(todo, key, prefs.geminiModel) { d, t -> _busy.value = Busy("AI prompts likh raha hai… $d/$t", d.toFloat() / t) }
                    p.scenes.mapIndexed { i, s -> if (!overwrite && s.prompt.isNotBlank()) s else ai[i] }
                } else {
                    p.scenes.mapIndexed { i, s -> if (s.prompt.isNotBlank()) s else s.copy(prompt = PromptGenerator.offline(p, i, s)) }
                }
            }
            update { it.copy(scenes = it.scenes.mapIndexed { i, s -> s.copy(prompt = scenes.getOrNull(i)?.prompt ?: s.prompt) }) }
            toast("${scenes.size} image prompts ready!")
        } catch (e: Exception) {
            toast("Prompt error: ${e.message}. Offline prompts use kar rahe hain.")
            update { it.copy(scenes = it.scenes.mapIndexed { i, s -> if (s.prompt.isBlank()) s.copy(prompt = PromptGenerator.offline(it, i, s)) else s }) }
        } finally { _busy.value = null }
    }

    // ------------------------------------------------------------ images

    fun setImage(index: Int, uri: Uri) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        val scene = p.scenes.getOrNull(index) ?: return@launch
        val path = withContext(Dispatchers.IO) { repo.importFile(p.id, uri, "img_${scene.id}") }
        updateScene(index) { it.copy(imagePath = path) }
    }

    fun removeImage(index: Int) = updateScene(index) { s ->
        s.imagePath?.let { File(it).delete() }; s.copy(imagePath = null)
    }

    /**
     * Bulk upload: images are sorted by file name (1, 2, 10 natural order) and placed
     * into scenes starting at the first scene without an image (or [startAt]).
     */
    fun bulkImages(uris: List<Uri>, startAt: Int? = null) = viewModelScope.launch {
        val p = _current.value ?: return@launch
        if (uris.isEmpty()) return@launch
        _busy.value = Busy("${uris.size} images import ho rahi hain…", 0f)
        try {
            val sorted = withContext(Dispatchers.IO) {
                uris.map { it to (repo.displayName(it) ?: "") }.sortedWith { a, b -> naturalCompare(a.second, b.second) }.map { it.first }
            }
            val targets = if (startAt != null) (startAt until p.scenes.size).toList()
            else p.scenes.indices.filter { p.scenes[it].imagePath == null }.ifEmpty { p.scenes.indices.toList() }
            var n = 0
            withContext(Dispatchers.IO) {
                sorted.zip(targets).forEachIndexed { k, (uri, idx) ->
                    val scene = p.scenes[idx]
                    val path = repo.importFile(p.id, uri, "img_${scene.id}")
                    withContext(Dispatchers.Main) { updateScene(idx) { it.copy(imagePath = path) } }
                    n++
                    _busy.value = Busy("Images import… ${k + 1}/${sorted.size}", (k + 1f) / sorted.size)
                }
            }
            val extra = sorted.size - n
            toast("$n images lag gayi" + if (extra > 0) " ($extra extra images chhod di)" else "")
        } finally { _busy.value = null }
    }

    // ------------------------------------------------------------ export

    fun startExport(quality: Int, fps: Int) {
        val p = _current.value ?: return
        if (_export.value.running) return
        prefs.exportQuality = quality; prefs.exportFps = fps
        val missing = p.scenes.count { it.imagePath == null }
        if (missing > 0) toast("$missing scenes me image nahi hai — placeholder dikhega")
        _export.value = ExportState(running = true, label = "Shuru…")
        viewModelScope.launch(Dispatchers.Default) {
            val out = File(repo.dir(p.id), "export.mp4")
            val ex = VideoExporter(getApplication<Application>(), p, quality, fps)
            exporter = ex
            try {
                ex.export(out) { pr, label -> _export.value = _export.value.copy(progress = pr, label = label) }
                _export.value = _export.value.copy(label = "Gallery me save ho raha hai…")
                val uri = withContext(Dispatchers.IO) { VideoExporter.saveToGallery(getApplication<Application>(), out, p.title) }
                _export.value = ExportState(running = false, progress = 1f, label = "Video ready!", file = out, galleryUri = uri)
            } catch (e: ExportCancelled) {
                _export.value = ExportState(label = "Cancel ho gaya")
            } catch (e: Throwable) {
                _export.value = ExportState(error = e.message ?: e.javaClass.simpleName)
            } finally { exporter = null }
        }
    }

    fun cancelExport() { exporter?.cancelled = true }

    companion object {
        fun naturalCompare(a: String, b: String): Int {
            val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ra.size, rb.size)) {
                val x = ra[i]; val y = rb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    (x.toBigInteger().compareTo(y.toBigInteger()))
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return ra.size - rb.size
        }
    }
}
