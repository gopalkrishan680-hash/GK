package com.gk.facelessstudio.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gk.facelessstudio.data.Repository
import com.gk.facelessstudio.engine.AudioDecoder
import com.gk.facelessstudio.engine.Director
import com.gk.facelessstudio.engine.ScriptAnalyzer
import com.gk.facelessstudio.engine.VoiceAnalyzer
import com.gk.facelessstudio.engine.VoiceInfo
import com.gk.facelessstudio.model.Aspect
import com.gk.facelessstudio.model.Pacing
import com.gk.facelessstudio.model.Project
import com.gk.facelessstudio.model.SceneType
import com.gk.facelessstudio.model.Transition
import com.gk.facelessstudio.render.Fonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    val fonts by lazy { Fonts(app.assets) }

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects

    private val _project = MutableStateFlow(repo.newProject())
    val project: StateFlow<Project> = _project

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast

    private val voiceCache = HashMap<String, VoiceInfo>()

    init { refresh() }

    fun refresh() = viewModelScope.launch { _projects.value = withContext(Dispatchers.IO) { repo.all() } }

    fun consumeToast() { _toast.value = null }
    fun toast(msg: String) { _toast.value = msg }

    fun newProject() { _project.value = repo.newProject() }

    fun open(id: String) { repo.load(id)?.let { _project.value = it } }

    fun delete(id: String) = viewModelScope.launch(Dispatchers.IO) { repo.delete(id); refresh() }

    fun duplicate(p: Project) = viewModelScope.launch(Dispatchers.IO) { repo.duplicate(p); refresh() }

    private fun update(save: Boolean = true, f: (Project) -> Project) {
        _project.value = f(_project.value)
        if (save) persist()
    }

    private fun persist() {
        val p = _project.value
        if (p.script.isBlank() && p.images.isEmpty() && p.voicePath == null) return
        viewModelScope.launch(Dispatchers.IO) { repo.save(p) }
    }

    /** Synchronous save so the export service reads the latest edits. */
    fun saveNow() = repo.save(_project.value)

    fun setTitle(v: String) = update { it.copy(title = v) }
    fun setScript(v: String) = update { it.copy(script = v) }
    fun setAspect(v: Aspect) = update { it.copy(aspect = v) }
    fun setTheme(v: String) = update { it.copy(themeId = v) }
    fun setPacing(v: Pacing) = update { it.copy(pacing = v) }
    fun setCaptions(v: Boolean) = update { it.copy(captions = v) }
    fun setSfx(v: Boolean) = update { it.copy(sfx = v) }
    fun setGrain(v: Boolean) = update { it.copy(grain = v) }
    fun setProgressBar(v: Boolean) = update { it.copy(progressBar = v) }
    fun setHandle(v: String) = update { it.copy(handle = v.trim()) }
    fun setMusicVolume(v: Float) = update { it.copy(musicVolume = v) }

    fun pickVoice(uri: Uri) = viewModelScope.launch {
        _busy.value = "Loading voiceover…"
        val res = withContext(Dispatchers.IO) { repo.import(_project.value.id, uri, "voice") }
        if (res == null) { _toast.value = "Could not open the audio file"; _busy.value = null; return@launch }
        update { it.copy(voicePath = res.first, voiceName = res.second) }
        analyzeVoice()
        _busy.value = null
    }

    fun removeVoice() = update { it.copy(voicePath = null, voiceName = null) }

    fun pickMusic(uri: Uri) = viewModelScope.launch {
        _busy.value = "Loading music…"
        val res = withContext(Dispatchers.IO) { repo.import(_project.value.id, uri, "music") }
        if (res == null) _toast.value = "Could not open the music file" else update { it.copy(musicPath = res.first, musicName = res.second) }
        _busy.value = null
    }

    fun removeMusic() = update { it.copy(musicPath = null, musicName = null) }

    fun addImages(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _busy.value = "Adding images…"
        val paths = withContext(Dispatchers.IO) { uris.mapNotNull { repo.import(_project.value.id, it, "img")?.first } }
        update { it.copy(images = it.images + paths) }
        _busy.value = null
    }

    fun removeImage(i: Int) = update { p ->
        p.images.getOrNull(i)?.let { File(it).delete() }
        p.copy(images = p.images.filterIndexed { idx, _ -> idx != i })
    }

    fun moveImage(i: Int, delta: Int) = update { p ->
        val j = (i + delta).coerceIn(0, p.images.lastIndex)
        val l = p.images.toMutableList(); val x = l.removeAt(i); l.add(j, x)
        p.copy(images = l)
    }

    private val _voiceInfo = MutableStateFlow<VoiceInfo?>(null)
    val voiceInfo: StateFlow<VoiceInfo?> = _voiceInfo

    private suspend fun analyzeVoice(): VoiceInfo? {
        val path = _project.value.voicePath ?: return null.also { _voiceInfo.value = null }
        voiceCache[path]?.let { _voiceInfo.value = it; return it }
        return withContext(Dispatchers.Default) {
            runCatching { VoiceAnalyzer.analyze(AudioDecoder.decode(path)) }.getOrNull()
        }?.also { voiceCache[path] = it; _voiceInfo.value = it } ?: run { _toast.value = "Could not read the voiceover"; null }
    }

    /** Runs the auto-editor. */
    fun generate(onDone: () -> Unit) = viewModelScope.launch {
        if (_project.value.script.isBlank()) { _toast.value = "Write or paste a script first"; return@launch }
        _busy.value = "Reading your script and building scenes…"
        val voice = analyzeVoice()
        val built = withContext(Dispatchers.Default) { Director.build(_project.value, voice) }
        _project.value = built
        withContext(Dispatchers.IO) { repo.save(built) }
        _busy.value = null
        refresh()
        onDone()
    }

    /** New random look for the entire video, same timing. */
    fun shuffle() = viewModelScope.launch {
        _busy.value = "Creating new animations…"
        val voice = analyzeVoice()
        val p = _project.value.copy(seed = System.nanoTime())
        _project.value = withContext(Dispatchers.Default) { Director.build(p, voice) }
        persist(); _busy.value = null
    }

    fun reroll(i: Int) = update { Director.reroll(it, i) }

    fun setSceneType(i: Int, t: SceneType) = update { p ->
        val l = p.scenes.toMutableList()
        val s = l[i]
        var lines = s.lines
        var title = s.title
        // fill in content the new layout needs
        when (t) {
            SceneType.QUESTION_LIST -> if (lines.isEmpty() || s.type != SceneType.QUESTION_LIST) lines = listOf(s.text)
            SceneType.CHECKLIST -> { lines = p.chapters.ifEmpty { ScriptAnalyzer.keywords(s.text, 4) }; title = p.title.ifBlank { "FULL GUIDE" }.uppercase() }
            SceneType.MINDMAP -> ScriptAnalyzer.detectList(s.text).let {
                title = (it?.first?.ifBlank { null } ?: ScriptAnalyzer.headline(s.text, 4)).uppercase()
                lines = it?.second ?: ScriptAnalyzer.keywords(s.text, 3).map { w -> w.replaceFirstChar { c -> c.uppercase() } }
            }
            SceneType.BIG_STAT -> ScriptAnalyzer.detectStat(s.text)?.let { st ->
                lines = listOf(st.prefix, st.number.toString(), st.decimals.toString(), st.grouped.toString(), st.suffix); title = st.label
            } ?: run { _toast.value = "No number found in this line"; return@update p }
            SceneType.DATE_STAMP -> lines = ScriptAnalyzer.detectDate(s.text) ?: listOf(ScriptAnalyzer.headline(s.text, 2), "")
            SceneType.VERSUS -> lines = (ScriptAnalyzer.detectVersus(s.text)?.toList() ?: ScriptAnalyzer.keywords(s.text, 2)).map { it.uppercase() }
            SceneType.QUOTE -> lines = listOf(ScriptAnalyzer.detectQuote(s.text) ?: s.text)
            SceneType.SPLIT_ARROW -> lines = (ScriptAnalyzer.detectCause(s.text)?.toList() ?: ScriptAnalyzer.keywords(s.text, 2)).map { it.uppercase() }
            SceneType.CHAPTER -> title = p.chapters.getOrNull(s.chapterIndex) ?: ScriptAnalyzer.headline(s.text, 3)
            SceneType.OUTRO -> title = p.handle
            else -> title = if (title.isBlank()) ScriptAnalyzer.headline(s.text, if (t == SceneType.HEADLINE_IMAGE) 2 else 4) else title
        }
        if (t.needsImage && p.images.isEmpty()) { _toast.value = "This layout needs an image"; return@update p }
        l[i] = s.copy(type = t, lines = lines, title = title, lineStarts = if (t == SceneType.QUESTION_LIST && lines.size != s.lineStarts.size) lines.indices.map { it / lines.size.toFloat() } else s.lineStarts,
            imageIndex = if (s.imageIndex < 0 && p.images.isNotEmpty()) 0 else s.imageIndex)
        p.copy(scenes = l)
    }

    fun setSceneTransition(i: Int, t: Transition) = update { p ->
        val l = p.scenes.toMutableList(); l[i] = l[i].copy(transition = t); p.copy(scenes = l)
    }

    fun setSceneImage(i: Int, img: Int) = update { p ->
        val l = p.scenes.toMutableList(); l[i] = l[i].copy(imageIndex = img); p.copy(scenes = l)
    }

    fun setSceneTitle(i: Int, title: String) = update { p ->
        val l = p.scenes.toMutableList(); l[i] = l[i].copy(title = title); p.copy(scenes = l)
    }

    // ---------------- extra outputs ----------------

    fun imagePrompts(style: String): List<Pair<String, String>> {
        val p = _project.value
        val chunks = ScriptAnalyzer.analyze(p.script, (p.pacing.maxSceneSec * 2.5f).toInt()).chunks
        val ratio = p.aspect.sub
        return chunks.map { it.text to ScriptAnalyzer.imagePrompt(it.text, style, ratio) }
    }

    fun srt(): String {
        val sb = StringBuilder()
        var n = 1
        for (s in _project.value.scenes) {
            if (s.type == SceneType.OUTRO) continue
            sb.append(n++).append('\n').append(ts(s.startMs, true)).append(" --> ").append(ts(s.endMs, true)).append('\n')
                .append(s.text).append("\n\n")
        }
        return sb.toString()
    }

    /** YouTube description chapters ("0:00 Intro"). */
    fun youtubeChapters(): String {
        val p = _project.value
        val starts = p.scenes.filter { it.chapterIndex >= 0 }.groupBy { it.chapterIndex }.mapValues { e -> e.value.minOf { it.startMs } }
        if (starts.isEmpty()) return ""
        val lines = mutableListOf<String>()
        if ((starts.values.minOrNull() ?: 0) > 0) lines += "0:00 Intro"
        starts.toSortedMap().forEach { (ci, ms) -> lines += "${ts(if (lines.isEmpty()) 0 else ms, false)} ${p.chapters.getOrElse(ci) { "Part ${ci + 1}" }}" }
        return lines.joinToString("\n")
    }

    private fun ts(ms: Long, srt: Boolean): String {
        val h = ms / 3_600_000; val m = (ms / 60_000) % 60; val s = (ms / 1000) % 60; val milli = ms % 1000
        return if (srt) String.format("%02d:%02d:%02d,%03d", h, m, s, milli)
        else if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    fun writeShareFile(name: String, content: String): File =
        File(getApplication<Application>().cacheDir, name).apply { writeText(content) }
}
