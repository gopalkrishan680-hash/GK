package com.gk.facelessstudio.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gk.facelessstudio.data.Repository
import com.gk.facelessstudio.engine.AiImages
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
    fun setCaptionMode(v: com.gk.facelessstudio.model.CaptionMode) = update { it.copy(captionMode = v) }
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
        update { it.copy(images = it.images + paths, lineImages = false) }
        _busy.value = null
    }

    fun removeImage(i: Int) = update { p ->
        p.images.getOrNull(i)?.let { File(it).delete() }
        p.copy(images = p.images.filterIndexed { idx, _ -> idx != i }, lineImages = false)
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
            SceneType.DONUT -> ScriptAnalyzer.detectPercent(s.text)?.let { lines = listOf(it.first.toString()); title = it.second }
                ?: run { _toast.value = "No percentage found in this line"; return@update p }
            SceneType.BAR_CHART -> ScriptAnalyzer.detectNumbers(s.text)?.let { n -> lines = n.map { "${it.first}|${it.second}" }; title = ScriptAnalyzer.headline(s.text, 4) }
                ?: run { _toast.value = "This line needs at least two numbers"; return@update p }
            SceneType.TIMELINE -> ScriptAnalyzer.detectYears(s.text)?.let { y -> lines = y.map { "${it.first}|${it.second}" } }
                ?: run { _toast.value = "This line needs at least two years"; return@update p }
            SceneType.KEY_POINTS -> { title = ScriptAnalyzer.headline(s.text, 4); lines = ScriptAnalyzer.keywords(s.text, 3).map { it.replaceFirstChar { c -> c.uppercase() } } }
            SceneType.KEN_BURNS, SceneType.POLAROID -> title = ScriptAnalyzer.headline(s.text, 4)
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
        val a = Director.analysisFor(p)
        val topic = p.title.ifBlank { a.chapters.firstOrNull() ?: "" }
        return a.chunks.map { it.text to ScriptAnalyzer.imagePrompt(it.text, style, p.aspect.sub, topic, a.chapters.getOrNull(it.chapterIndex) ?: "") }
    }

    // ---------------- AI image generation ----------------

    data class AiProgress(val done: Int, val total: Int, val message: String)
    private val _ai = MutableStateFlow<AiProgress?>(null)
    val ai: StateFlow<AiProgress?> = _ai
    private var aiJob: kotlinx.coroutines.Job? = null

    fun cancelAi() { aiJob?.cancel(); _ai.value = null }

    private fun imageSize(p: Project) = when (p.aspect) {
        com.gk.facelessstudio.model.Aspect.LANDSCAPE -> 1280 to 720
        com.gk.facelessstudio.model.Aspect.PORTRAIT -> 720 to 1280
        com.gk.facelessstudio.model.Aspect.SQUARE -> 1024 to 1024
    }

    /** Makes one picture for every line of the script, so each scene shows exactly what is being said. */
    fun generateAiImages(style: String) {
        if (aiJob?.isActive == true) return
        val p0 = _project.value
        if (p0.script.isBlank()) { _toast.value = "Write your script first"; return }
        aiJob = viewModelScope.launch {
            val a = Director.analysisFor(p0)
            val step = kotlin.math.ceil(a.chunks.size / 60.0).toInt().coerceAtLeast(1)
            val pieces = a.chunks.chunked(step)
            val total = pieces.size
            val topic = p0.title.ifBlank { a.chapters.firstOrNull() ?: "" }
            _ai.value = AiProgress(0, total, "Writing image prompts from your script…")
            val lines = pieces.map { g -> g.joinToString(" ") { it.text } }
            val prompts = withContext(Dispatchers.IO) {
                lines.chunked(12).flatMapIndexed { bi, batch ->
                    val enhanced = AiImages.enhancePrompts(batch, topic, style)
                    batch.mapIndexed { j, line ->
                        val chunk = pieces[bi * 12 + j].first()
                        enhanced?.getOrNull(j)?.let { AiImages.finalPrompt(it, style, p0.aspect.sub) }
                            ?: ScriptAnalyzer.imagePrompt(line, style, p0.aspect.sub, topic, a.chapters.getOrNull(chunk.chapterIndex) ?: "")
                    }
                }
            }
            withContext(Dispatchers.IO) { File(repo.dir(p0.id), "prompts.txt").writeText(prompts.joinToString("\n")) }
            val (w, h) = imageSize(p0)
            val results = arrayOfNulls<String>(total)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val gate = kotlinx.coroutines.sync.Semaphore(2)
            kotlinx.coroutines.coroutineScope {
                prompts.forEachIndexed { i, prompt ->
                    launch(Dispatchers.IO) {
                        gate.acquire()
                        try {
                            val f = File(repo.dir(p0.id), "ai_${System.nanoTime()}_$i.jpg")
                            if (AiImages.generate(prompt, w, h, (p0.seed % 100000).toInt() + i, f)) results[i] = f.absolutePath
                        } finally { gate.release() }
                        val n = done.incrementAndGet()
                        _ai.value = AiProgress(n, total, "Generating image $n of $total…")
                    }
                }
            }
            val ok = results.count { it != null }
            if (ok == 0) {
                _ai.value = null
                _toast.value = "Could not reach the AI image service. Check your internet and try again."
                return@launch
            }
            // fill any failed slot with its nearest neighbour so line->image mapping stays aligned
            val filled = results.indices.map { i -> results[i] ?: results.indices.sortedBy { kotlin.math.abs(it - i) }.firstNotNullOf { results[it] } }
            update { it.copy(images = filled, lineImages = true, imageStep = step) }
            if (_project.value.scenes.isNotEmpty()) {
                val voice = analyzeVoice()
                _project.value = withContext(Dispatchers.Default) { Director.build(_project.value, voice) }
                persist()
            }
            _ai.value = null
            _toast.value = if (ok == total) "$total images ready ✅" else "$ok of $total images ready (others reused nearby images)"
        }
    }

    /** New AI picture for one scene (fresh seed), keeping everything else. */
    fun regenerateSceneImage(i: Int, style: String = "Cinematic realistic photo") {
        val p0 = _project.value
        val s = p0.scenes.getOrNull(i) ?: return
        if (aiJob?.isActive == true) return
        aiJob = viewModelScope.launch {
            _ai.value = AiProgress(0, 1, "Generating a new image for this scene…")
            val topic = p0.title.ifBlank { p0.chapters.firstOrNull() ?: "" }
            val prompt = withContext(Dispatchers.IO) {
                AiImages.enhancePrompts(listOf(s.text), topic, style)?.firstOrNull()?.let { AiImages.finalPrompt(it, style, p0.aspect.sub) }
                    ?: ScriptAnalyzer.imagePrompt(s.text, style, p0.aspect.sub, topic, p0.chapters.getOrNull(s.chapterIndex) ?: "")
            }
            val (w, h) = imageSize(p0)
            val f = File(repo.dir(p0.id), "ai_${System.nanoTime()}.jpg")
            val ok = withContext(Dispatchers.IO) { AiImages.generate(prompt, w, h, (System.nanoTime() % 100000).toInt(), f) }
            _ai.value = null
            if (!ok) { _toast.value = "Could not reach the AI image service"; return@launch }
            update { p ->
                val idx = p.scenes[i].imageIndex
                if (idx in p.images.indices) {
                    // replace in place so the line->image mapping (and Shuffle) keeps the new picture
                    p.copy(images = p.images.toMutableList().also { it[idx] = f.absolutePath })
                } else {
                    val imgs = p.images + f.absolutePath
                    val l = p.scenes.toMutableList(); l[i] = l[i].copy(imageIndex = imgs.lastIndex)
                    p.copy(images = imgs, scenes = l)
                }
            }
        }
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
