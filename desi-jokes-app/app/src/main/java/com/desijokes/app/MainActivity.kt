package com.desijokes.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : Activity(), SensorEventListener {

    private lateinit var data: JokeRepo.Data
    private var userJokes = listOf<Joke>()
    private var visible = listOf<Joke>()
    private var index = 0
    private var selected = ALL
    private var query = ""

    private lateinit var prefs: SharedPreferences
    private lateinit var jokeView: TextView
    private lateinit var categoryView: TextView
    private lateinit var counterView: TextView
    private lateinit var subtitle: TextView
    private lateinit var favBtn: TextView
    private lateinit var speakBtn: TextView
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var sensors: SensorManager? = null
    private var lastShake = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = JokeRepo.prefs(this)

        jokeView = findViewById(R.id.joke)
        categoryView = findViewById(R.id.category)
        counterView = findViewById(R.id.counter)
        subtitle = findViewById(R.id.subtitle)
        favBtn = findViewById(R.id.btnFav)
        speakBtn = findViewById(R.id.btnSpeak)
        chips = findViewById(R.id.chips)
        search = findViewById(R.id.search)

        data = JokeRepo.load(this)
        userJokes = JokeRepo.userJokes(this)
        applyFontSize()

        findViewById<View>(R.id.btnNext).setOnClickListener { step(1) }
        findViewById<View>(R.id.btnPrev).setOnClickListener { step(-1) }
        findViewById<View>(R.id.btnRandom).setOnClickListener { random() }
        favBtn.setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copy() }
        findViewById<View>(R.id.btnShare).setOnClickListener { shareText() }
        findViewById<View>(R.id.btnImage).setOnClickListener { shareImage() }
        speakBtn.setOnClickListener { speak() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { toggleSearch() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refresh(shuffle = false)
            }
        })

        setupSwipe()
        sensors = getSystemService(SensorManager::class.java)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val hi = Locale("hi", "IN")
                val r = tts?.setLanguage(hi)
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.language = Locale("en", "IN")
                tts?.setSpeechRate(0.95f)
                ttsReady = true
            }
        }

        if (intent?.getBooleanExtra(DailyJoke.EXTRA_OPEN_TODAY, false) == true) selected = TODAY
        buildChips()
        refresh(shuffle = true)
        maybeAskDaily()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(DailyJoke.EXTRA_OPEN_TODAY, false)) selectCategory(TODAY)
    }

    override fun onResume() {
        super.onResume()
        updateShake()
    }

    override fun onPause() {
        super.onPause()
        sensors?.unregisterListener(this)
        tts?.stop()
    }

    private fun updateShake() {
        sensors?.unregisterListener(this)
        if (shakeOn()) sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- data ----------

    private fun adultUnlocked() = prefs.getBoolean("adult_ok", false)
    private fun shakeOn() = prefs.getBoolean("shake_on", true)
    private fun favs(): MutableSet<String> = prefs.getStringSet("favs", emptySet())!!.toMutableSet()
    private fun seen(): MutableSet<String> = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
    private fun everything() = data.jokes + userJokes
    private fun cat(id: String) = if (id == JokeRepo.MINE) JokeRepo.mineCategory else data.categories.first { it.id == id }

    private fun refresh(shuffle: Boolean) {
        val favSet = favs()
        var list = when (selected) {
            ALL -> everything().filter { !cat(it.categoryId).adult || adultUnlocked() }
            FAVS -> everything().filter { it.id in favSet }
            TODAY -> listOf(JokeRepo.jokeOfDay(this))
            else -> everything().filter { it.categoryId == selected }
        }
        if (query.isNotEmpty()) list = list.filter { it.text.contains(query, ignoreCase = true) }
        visible = if (shuffle) {
            // jokes you haven't seen yet come first, so repeats are rare
            val s = seen()
            list.shuffled().sortedBy { it.id in s }
        } else list
        index = 0
        render()
    }

    // ---------- ui ----------

    private fun buildChips() {
        chips.removeAllViews()
        addChip(TODAY, "☀️ Aaj Ka")
        addChip(ALL, "🔥 Sab")
        addChip(FAVS, "❤️ Saved")
        data.categories.forEach { addChip(it.id, "${it.emoji} ${it.name}") }
        addChip(JokeRepo.MINE, "✍️ Mere Jokes")
    }

    private fun addChip(id: String, label: String) {
        val tv = TextView(this).apply {
            text = label
            textSize = 14f
            setPadding(dp(16), dp(9), dp(16), dp(9))
            val on = id == selected
            setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
            setTextColor(if (on) 0xFF121016.toInt() else getColor(R.color.text))
            setOnClickListener { selectCategory(id) }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(8)
        chips.addView(tv, lp)
    }

    private fun selectCategory(id: String) {
        val isAdult = data.categories.firstOrNull { it.id == id }?.adult == true
        if (isAdult && !adultUnlocked()) {
            dialog().setTitle("🔞 Sirf 18+ ke liye")
                .setMessage("Is section mein double meaning aur adult jokes hain. Kya aap 18 saal ya usse bade ho?")
                .setPositiveButton("Haan, 18+ hoon") { _, _ ->
                    prefs.edit().putBoolean("adult_ok", true).apply()
                    selectCategory(id)
                }
                .setNegativeButton("Nahi", null)
                .show()
            return
        }
        selected = id
        buildChips()
        refresh(shuffle = id != FAVS && id != JokeRepo.MINE)
    }

    private fun render() {
        tts?.stop()
        updateSubtitle()
        if (visible.isEmpty()) {
            categoryView.text = ""
            counterView.text = "0/0"
            jokeView.text = when {
                query.isNotEmpty() -> "Kuch nahi mila 🤷‍♂️\n\nKuch aur search karke dekho."
                selected == FAVS -> "Abhi koi joke save nahi kiya.\n\nKoi joke pasand aaye toh 🤍 dabao ya joke pe double-tap karo."
                selected == JokeRepo.MINE -> "Yahan aapke apne jokes aayenge.\n\nUpar ⋮ menu mein “✍️ Apna joke likho” dabao."
                else -> "Yahan abhi koi joke nahi hai."
            }
            favBtn.text = "🤍\nSave"
            return
        }
        val j = visible[index]
        val c = cat(j.categoryId)
        categoryView.text = (if (selected == TODAY) "☀️ AAJ KA JOKE • " else "") + "${c.emoji} ${c.name}".uppercase()
        categoryView.setTextColor(getColor(if (c.adult) R.color.adult else R.color.accent2))
        counterView.text = "${index + 1}/${visible.size}"
        jokeView.text = j.text
        favBtn.text = if (j.id in favs()) "❤️\nSaved" else "🤍\nSave"
        jokeView.alpha = 0f
        jokeView.animate().alpha(1f).setDuration(180).start()
        val s = seen()
        if (s.add(j.id)) prefs.edit().putStringSet("seen", s).apply()
    }

    private fun updateSubtitle() {
        val total = data.jokes.size + userJokes.size
        subtitle.text = "$total jokes • ${seen().size} dekhe • Offline"
    }

    private fun step(d: Int) {
        if (visible.isEmpty()) return
        index = (index + d + visible.size) % visible.size
        render()
    }

    private fun random() {
        if (visible.size < 2) return
        var n: Int
        do { n = visible.indices.random() } while (n == index)
        index = n
        render()
    }

    private fun current(): Joke? = visible.getOrNull(index)

    private fun toggleFav() {
        val j = current() ?: return
        val set = favs()
        val added = !set.remove(j.id)
        if (added) set.add(j.id)
        prefs.edit().putStringSet("favs", set).apply()
        favBtn.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        favBtn.text = if (added) "❤️\nSaved" else "🤍\nSave"
        if (added) Toast.makeText(this, "Saved ❤️", Toast.LENGTH_SHORT).show()
    }

    private fun copy() {
        val j = current() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("joke", j.text))
        Toast.makeText(this, "Copy ho gaya 😎", Toast.LENGTH_SHORT).show()
    }

    private fun shareText() {
        val j = current() ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${j.text}\n\n😂")
        }
        startActivity(Intent.createChooser(i, "Joke bhejo"))
    }

    private fun shareImage() {
        val j = current() ?: return
        try {
            val uri = CardRenderer.render(this, j, cat(j.categoryId))
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("joke", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Photo share karo (Status ke liye best)"))
        } catch (e: Exception) {
            Toast.makeText(this, "Photo nahi ban paayi 😕", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speak() {
        val j = current() ?: return
        val t = tts
        if (t == null || !ttsReady) {
            Toast.makeText(this, "Phone mein text-to-speech nahi mila", Toast.LENGTH_SHORT).show()
            return
        }
        if (t.isSpeaking) { t.stop(); return }
        val clean = j.text.replace(Regex("[“”\"]"), "").replace("\n", ". ")
        t.speak(clean, TextToSpeech.QUEUE_FLUSH, null, j.id)
    }

    private fun toggleSearch() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (search.visibility == View.VISIBLE) {
            search.setText("")
            search.visibility = View.GONE
            imm.hideSoftInputFromWindow(search.windowToken, 0)
        } else {
            search.visibility = View.VISIBLE
            search.requestFocus()
            imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ---------- menu & settings ----------

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "✍️ Apna joke likho")
        m.menu.add(0, 2, 0, if (DailyJoke.isEnabled(this)) "🔔 Roz ka joke: ON (8 PM)" else "🔕 Roz ka joke: OFF")
        m.menu.add(0, 3, 0, if (shakeOn()) "📳 Hilao = naya joke: ON" else "📳 Hilao = naya joke: OFF")
        m.menu.add(0, 4, 0, "🔠 Text bada karo")
        m.menu.add(0, 5, 0, "🔡 Text chhota karo")
        m.menu.add(0, 6, 0, "🔄 Dekhe hue jokes reset karo")
        if (adultUnlocked()) m.menu.add(0, 7, 0, "🔒 18+ section lock karo")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> addJokeDialog()
                2 -> toggleDaily()
                3 -> {
                    prefs.edit().putBoolean("shake_on", !shakeOn()).apply()
                    updateShake()
                    Toast.makeText(this, if (shakeOn()) "Phone hilao, naya joke aayega 📳" else "Shake band", Toast.LENGTH_SHORT).show()
                }
                4 -> changeFont(+2f)
                5 -> changeFont(-2f)
                6 -> {
                    prefs.edit().remove("seen").apply()
                    refresh(shuffle = true)
                }
                7 -> {
                    prefs.edit().putBoolean("adult_ok", false).apply()
                    if (data.categories.firstOrNull { c -> c.id == selected }?.adult == true) selected = ALL
                    buildChips(); refresh(shuffle = true)
                    Toast.makeText(this, "18+ section lock ho gaya 🔒", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        m.show()
    }

    private fun addJokeDialog() {
        val input = EditText(this).apply {
            hint = "Apna joke yahan likho…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.muted))
        }
        val box = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        dialog().setTitle("✍️ Apna joke")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    JokeRepo.addUserJoke(this, t)
                    userJokes = JokeRepo.userJokes(this)
                    selectCategory(JokeRepo.MINE)
                    index = visible.indexOfFirst { it.text == t }.coerceAtLeast(0)
                    render()
                    Toast.makeText(this, "Joke save ho gaya ✍️", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteOwnJoke() {
        val j = current() ?: return
        if (j.categoryId != JokeRepo.MINE) return
        dialog().setTitle("Joke delete karein?")
            .setMessage(j.text)
            .setPositiveButton("Delete") { _, _ ->
                JokeRepo.deleteUserJoke(this, j.id)
                userJokes = JokeRepo.userJokes(this)
                refresh(shuffle = false)
            }
            .setNegativeButton("Rehne do", null)
            .show()
    }

    private fun maybeAskDaily() {
        if (prefs.getBoolean("asked_daily", false)) return
        prefs.edit().putBoolean("asked_daily", true).apply()
        dialog().setTitle("🔔 Roz ek joke?")
            .setMessage("Roz shaam 8 baje ek mast joke notification mein bhej doon?\n(Baad mein ⋮ menu se band kar sakte ho.)")
            .setPositiveButton("Haan bhejo") { _, _ -> enableDaily() }
            .setNegativeButton("Nahi", null)
            .show()
    }

    private fun toggleDaily() {
        if (DailyJoke.isEnabled(this)) {
            DailyJoke.setEnabled(this, false)
            Toast.makeText(this, "Roz ka joke band 🔕", Toast.LENGTH_SHORT).show()
        } else enableDaily()
    }

    private fun enableDaily() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        DailyJoke.setEnabled(this, true)
        Toast.makeText(this, "Done! Roz shaam 8 baje joke aayega 🔔", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIF) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) enableDaily()
        else Toast.makeText(this, "Notification permission nahi mili", Toast.LENGTH_SHORT).show()
    }

    private fun applyFontSize() {
        jokeView.textSize = prefs.getFloat("font", 21f)
    }

    private fun changeFont(delta: Float) {
        val f = (prefs.getFloat("font", 21f) + delta).coerceIn(15f, 31f)
        prefs.edit().putFloat("font", f).apply()
        applyFontSize()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    // ---------- gestures & shake ----------

    private fun setupSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > dp(60) && abs(vx) > abs(vy)) {
                    step(if (dx < 0) 1 else -1)
                    return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { toggleFav(); return true }
            override fun onLongPress(e: MotionEvent) { deleteOwnJoke() }
        })
        findViewById<View>(R.id.card).setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        jokeView.setOnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g > 2.6f && now - lastShake > 1200) {
            lastShake = now
            jokeView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (selected == TODAY) selectCategory(ALL)
            random()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ALL = "__all"
        const val FAVS = "__favs"
        const val TODAY = "__today"
        const val REQ_NOTIF = 7
    }
}
