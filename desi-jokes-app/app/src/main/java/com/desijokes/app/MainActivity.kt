package com.desijokes.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import kotlin.math.abs

data class Joke(val id: String, val text: String, val categoryId: String)
data class Category(val id: String, val name: String, val emoji: String, val adult: Boolean)

class MainActivity : Activity() {

    private val allJokes = mutableListOf<Joke>()
    private val categories = mutableListOf<Category>()
    private var visible = listOf<Joke>()
    private var index = 0
    private var selected = ALL
    private var query = ""

    private lateinit var prefs: SharedPreferences
    private lateinit var jokeView: TextView
    private lateinit var categoryView: TextView
    private lateinit var counterView: TextView
    private lateinit var favBtn: TextView
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("desi_jokes", Context.MODE_PRIVATE)

        jokeView = findViewById(R.id.joke)
        categoryView = findViewById(R.id.category)
        counterView = findViewById(R.id.counter)
        favBtn = findViewById(R.id.btnFav)
        chips = findViewById(R.id.chips)
        search = findViewById(R.id.search)

        loadJokes()
        findViewById<TextView>(R.id.subtitle).text = "${allJokes.size}+ jokes • Offline • No filter"
        buildChips()

        findViewById<View>(R.id.btnNext).setOnClickListener { step(1) }
        findViewById<View>(R.id.btnPrev).setOnClickListener { step(-1) }
        findViewById<View>(R.id.btnRandom).setOnClickListener { random() }
        favBtn.setOnClickListener { toggleFav() }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copy() }
        findViewById<View>(R.id.btnShare).setOnClickListener { share() }
        findViewById<View>(R.id.btnSearch).setOnClickListener { toggleSearch() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refresh(shuffle = false)
            }
        })

        setupSwipe()
        refresh(shuffle = true)
    }

    // ---------- data ----------

    private fun loadJokes() {
        val json = assets.open("jokes.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cats = JSONObject(json).getJSONArray("categories")
        for (i in 0 until cats.length()) {
            val c = cats.getJSONObject(i)
            val cat = Category(c.getString("id"), c.getString("name"), c.getString("emoji"), c.optBoolean("adult"))
            categories.add(cat)
            val jokes = c.getJSONArray("jokes")
            for (j in 0 until jokes.length()) {
                allJokes.add(Joke("${cat.id}_$j", jokes.getString(j), cat.id))
            }
        }
    }

    private fun adultUnlocked() = prefs.getBoolean("adult_ok", false)
    private fun favs(): MutableSet<String> = prefs.getStringSet("favs", emptySet())!!.toMutableSet()
    private fun cat(id: String) = categories.first { it.id == id }

    private fun refresh(shuffle: Boolean) {
        val favSet = favs()
        var list = when (selected) {
            ALL -> allJokes.filter { !cat(it.categoryId).adult || adultUnlocked() }
            FAVS -> allJokes.filter { it.id in favSet }
            else -> allJokes.filter { it.categoryId == selected }
        }
        if (query.isNotEmpty()) list = list.filter { it.text.contains(query, ignoreCase = true) }
        visible = if (shuffle) list.shuffled() else list
        index = 0
        render()
    }

    // ---------- ui ----------

    private fun buildChips() {
        chips.removeAllViews()
        addChip(ALL, "🔥 Sab")
        addChip(FAVS, "❤️ Saved")
        categories.forEach { addChip(it.id, "${it.emoji} ${it.name}") }
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
        val isAdult = categories.firstOrNull { it.id == id }?.adult == true
        if (isAdult && !adultUnlocked()) {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("🔞 Sirf 18+ ke liye")
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
        refresh(shuffle = id != FAVS)
    }

    private fun render() {
        if (visible.isEmpty()) {
            categoryView.text = ""
            counterView.text = "0/0"
            jokeView.text = when {
                selected == FAVS && query.isEmpty() -> "Abhi koi joke save nahi kiya.\n\nKoi joke pasand aaye toh 🤍 Save dabao."
                else -> "Kuch nahi mila 🤷‍♂️\n\nKuch aur search karke dekho."
            }
            favBtn.text = "🤍 Save"
            return
        }
        val j = visible[index]
        val c = cat(j.categoryId)
        categoryView.text = "${c.emoji} ${c.name}".uppercase()
        categoryView.setTextColor(getColor(if (c.adult) R.color.adult else R.color.accent2))
        counterView.text = "${index + 1}/${visible.size}"
        jokeView.text = j.text
        favBtn.text = if (j.id in favs()) "❤️ Saved" else "🤍 Save"
        jokeView.alpha = 0f
        jokeView.animate().alpha(1f).setDuration(180).start()
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
        if (!set.remove(j.id)) set.add(j.id)
        prefs.edit().putStringSet("favs", set).apply()
        render()
    }

    private fun copy() {
        val j = current() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("joke", j.text))
        Toast.makeText(this, "Copy ho gaya 😎", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val j = current() ?: return
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "${j.text}\n\n😂")
        }
        startActivity(Intent.createChooser(i, "Joke bhejo"))
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
        })
        val touch = View.OnTouchListener { _, ev -> detector.onTouchEvent(ev); false }
        findViewById<View>(R.id.card).setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
        jokeView.setOnTouchListener(touch)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ALL = "__all"
        const val FAVS = "__favs"
    }
}
