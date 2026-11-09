package com.gk.speakup.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * All local data (profile, progress, vocab, mistakes) in SharedPreferences.
 * Values are exposed as Compose state so screens update automatically.
 */
class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("speakup", Context.MODE_PRIVATE)

    var profile by mutableStateOf(loadProfile())
        private set
    var stats by mutableStateOf(loadStats())
        private set
    var levelResult by mutableStateOf(loadLevel())
        private set
    var vocab by mutableStateOf(loadVocab())
        private set
    var mistakes by mutableStateOf(loadMistakes())
        private set
    var sessions by mutableStateOf(loadSessions())
        private set

    // ------------------------------------------------------------ profile
    fun updateProfile(transform: (Profile) -> Profile) {
        profile = transform(profile)
        val p = profile
        prefs.edit()
            .putString("name", p.name).putString("goal", p.goal).putString("apiKey", p.apiKey)
            .putString("provider", p.provider).putString("geminiKey", p.geminiKey).putString("geminiModel", p.geminiModel)
            .putString("level", p.level).putBoolean("hinglish", p.hinglish).putString("persona", p.personaId)
            .putString("model", p.model).putString("accent", p.accent).putFloat("rate", p.speechRate)
            .putBoolean("handsFree", p.handsFree).putInt("dailyGoal", p.dailyGoalMin)
            .apply()
    }

    private fun loadProfile() = Profile(
        name = prefs.getString("name", "")!!,
        goal = prefs.getString("goal", "")!!,
        apiKey = prefs.getString("apiKey", "")!!,
        // Existing users who already set a Claude key stay on Claude.
        provider = prefs.getString("provider", null) ?: if (prefs.getString("apiKey", "").isNullOrBlank()) "gemini" else "claude",
        geminiKey = prefs.getString("geminiKey", "")!!,
        geminiModel = prefs.getString("geminiModel", "")!!,
        level = prefs.getString("level", "")!!,
        hinglish = prefs.getBoolean("hinglish", true),
        personaId = prefs.getString("persona", "emma")!!,
        model = prefs.getString("model", "claude-opus-5-5")!!,
        accent = prefs.getString("accent", "en-US")!!,
        speechRate = prefs.getFloat("rate", 0.95f),
        handsFree = prefs.getBoolean("handsFree", true),
        dailyGoalMin = prefs.getInt("dailyGoal", 10),
    )

    // ------------------------------------------------------------ level
    fun saveLevel(r: LevelResult) {
        levelResult = r
        val o = JSONObject()
            .put("level", r.level).put("overall", r.overall).put("grammar", r.grammar)
            .put("vocabulary", r.vocabulary).put("fluency", r.fluency).put("pronunciation", r.pronunciation)
            .put("strengths", JSONArray(r.strengths)).put("weaknesses", JSONArray(r.weaknesses))
            .put("focus_areas", JSONArray(r.focusAreas)).put("summary", r.summary)
            .put("advice_hinglish", r.adviceHinglish).put("at", r.at)
        prefs.edit().putString("levelResult", o.toString()).apply()
        updateProfile { it.copy(level = r.level) }
    }

    private fun loadLevel(): LevelResult? {
        val s = prefs.getString("levelResult", null) ?: return null
        return runCatching { levelFromJson(JSONObject(s)) }.getOrNull()
    }

    // ------------------------------------------------------------ stats
    /** Record speaking activity. Updates XP, streak and today's minutes. */
    fun addActivity(seconds: Long, words: Int, xp: Int, sessionDone: Boolean = false) {
        val today = LocalDate.now().toEpochDay()
        val s = stats
        val streak = when (s.lastDay) {
            today -> maxOf(s.streak, 1)
            today - 1 -> s.streak + 1
            else -> 1
        }
        stats = s.copy(
            xp = s.xp + xp,
            streak = streak,
            lastDay = today,
            sessions = s.sessions + if (sessionDone) 1 else 0,
            words = s.words + words,
            seconds = s.seconds + seconds,
            todayDay = today,
            todaySeconds = (if (s.todayDay == today) s.todaySeconds else 0) + seconds,
        )
        val n = stats
        prefs.edit().putString(
            "stats", JSONObject()
                .put("xp", n.xp).put("streak", n.streak).put("lastDay", n.lastDay).put("sessions", n.sessions)
                .put("words", n.words).put("seconds", n.seconds).put("todayDay", n.todayDay)
                .put("todaySeconds", n.todaySeconds).toString()
        ).apply()
    }

    /** Streak shown on screen: drops to 0 if a day was missed. */
    fun currentStreak(): Int {
        val today = LocalDate.now().toEpochDay()
        return if (stats.lastDay >= today - 1) stats.streak else 0
    }

    fun todayMinutes(): Int {
        val today = LocalDate.now().toEpochDay()
        return if (stats.todayDay == today) (stats.todaySeconds / 60).toInt() else 0
    }

    private fun loadStats(): Stats {
        val s = prefs.getString("stats", null) ?: return Stats()
        return runCatching {
            val o = JSONObject(s)
            Stats(
                o.optInt("xp"), o.optInt("streak"), o.optLong("lastDay", -1), o.optInt("sessions"),
                o.optInt("words"), o.optLong("seconds"), o.optLong("todayDay", -1), o.optLong("todaySeconds"),
            )
        }.getOrDefault(Stats())
    }

    // ------------------------------------------------------------ vocab
    fun addWords(words: List<VocabWord>) {
        val existing = vocab.map { it.word.lowercase() }.toMutableSet()
        val fresh = words.filter { it.word.isNotBlank() && existing.add(it.word.lowercase()) }
        if (fresh.isEmpty()) return
        vocab = fresh + vocab
        saveVocab()
    }

    fun updateWord(word: VocabWord) {
        vocab = vocab.map { if (it.word == word.word) word else it }
        saveVocab()
    }

    fun deleteWord(word: VocabWord) {
        vocab = vocab.filterNot { it.word == word.word }
        saveVocab()
    }

    private fun saveVocab() {
        val a = JSONArray()
        vocab.take(1000).forEach {
            a.put(JSONObject().put("word", it.word).put("meaning", it.meaning).put("example", it.example)
                .put("box", it.box).put("at", it.at))
        }
        prefs.edit().putString("vocab", a.toString()).apply()
    }

    private fun loadVocab(): List<VocabWord> = loadList("vocab") {
        VocabWord(it.optString("word"), it.optString("meaning"), it.optString("example"), it.optInt("box"), it.optLong("at"))
    }

    // ------------------------------------------------------------ mistakes
    fun addMistakes(list: List<Correction>) {
        if (list.isEmpty()) return
        mistakes = (list + mistakes).take(500)
        val a = JSONArray()
        mistakes.forEach {
            a.put(JSONObject().put("original", it.original).put("corrected", it.corrected)
                .put("explanation", it.explanation).put("type", it.type).put("at", it.at))
        }
        prefs.edit().putString("mistakes", a.toString()).apply()
    }

    fun clearMistakes() {
        mistakes = emptyList()
        prefs.edit().remove("mistakes").apply()
    }

    private fun loadMistakes(): List<Correction> = loadList("mistakes") {
        Correction(it.optString("original"), it.optString("corrected"), it.optString("explanation"),
            it.optString("type"), it.optLong("at"))
    }

    // ------------------------------------------------------------ sessions
    fun addSession(r: SessionRecord) {
        sessions = (listOf(r) + sessions).take(100)
        val a = JSONArray()
        sessions.forEach {
            a.put(JSONObject().put("title", it.title).put("summary", it.summary).put("score", it.score)
                .put("minutes", it.minutes).put("at", it.at))
        }
        prefs.edit().putString("sessions", a.toString()).apply()
    }

    private fun loadSessions(): List<SessionRecord> = loadList("sessions") {
        SessionRecord(it.optString("title"), it.optString("summary"), it.optInt("score"), it.optInt("minutes"), it.optLong("at"))
    }

    // ------------------------------------------------------------ cached word of the day
    fun dailyCache(): Pair<Long, String>? {
        val day = prefs.getLong("dailyDay", -1)
        val json = prefs.getString("dailyJson", null) ?: return null
        return day to json
    }

    fun saveDaily(json: String) {
        prefs.edit().putLong("dailyDay", LocalDate.now().toEpochDay()).putString("dailyJson", json).apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
        profile = Profile(); stats = Stats(); levelResult = null
        vocab = emptyList(); mistakes = emptyList(); sessions = emptyList()
    }

    private fun <T> loadList(key: String, map: (JSONObject) -> T): List<T> {
        val s = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { map(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    companion object {
        fun levelFromJson(o: JSONObject) = LevelResult(
            level = o.optString("level", "B1"),
            overall = o.optInt("overall"),
            grammar = o.optInt("grammar"),
            vocabulary = o.optInt("vocabulary"),
            fluency = o.optInt("fluency"),
            pronunciation = o.optInt("pronunciation"),
            strengths = o.optJSONArray("strengths").strings(),
            weaknesses = o.optJSONArray("weaknesses").strings(),
            focusAreas = o.optJSONArray("focus_areas").strings(),
            summary = o.optString("summary"),
            adviceHinglish = o.optString("advice_hinglish"),
            at = o.optLong("at", System.currentTimeMillis()),
        )
    }
}

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
