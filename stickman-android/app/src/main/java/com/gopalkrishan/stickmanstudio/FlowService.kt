package com.gopalkrishan.stickmanstudio

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the image app the user picked (Google Flow) for Auto Pilot: opens it, types the sheet prompt,
 * presses Generate, waits for the picture, downloads it and hands the file back to Stickman Studio.
 *
 * Which boxes and buttons to use is learned once: in "Teach Flow" the user does one generation by hand and
 * the service remembers the prompt box, the Generate button, the picture and the Download button they tap.
 * When a remembered element is not found (the app was updated), common labels are tried, and as a last
 * resort the picture is cut out of a screenshot.
 */
class FlowService : AccessibilityService() {

    data class Result(val bytes: ByteArray, val mime: String)
    interface Callback {
        fun progress(msg: String)
        fun done(r: Result)
        fun failed(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    // learning
    private var learnStep = -1
    private val learned = JSONObject()
    private var overlay: LinearLayout? = null
    private var overlayText: TextView? = null
    private var learnDone: ((String) -> Unit)? = null

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Flow helper connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (learnStep < 0) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != flowPackage(this)) return
        val step = STEPS[learnStep]
        val ok = when (step) {
            "prompt" -> e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            else -> e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        }
        if (!ok) return
        val src = e.source ?: return
        if (step == "prompt" && !src.isEditable && findEditable(src) == null) return
        if (step != "prompt" && src.isEditable) return   // tapping the prompt box again while typing
        learned.put(step, describe(src))
        nextLearnStep()
    }

    // ---------- teach mode ----------

    fun startLearning(onDone: (String) -> Unit) {
        val pkg = flowPackage(this)
        if (pkg.isEmpty()) { onDone("{\"ok\":false,\"msg\":\"Pick the Flow app first\"}"); return }
        learnDone = onDone
        learned.keys().asSequence().toList().forEach { learned.remove(it) }
        learnStep = 0
        launch(pkg)
        main.postDelayed({ showOverlay(); updateOverlay() }, 800)
    }

    private fun nextLearnStep() {
        learnStep++
        if (learnStep >= STEPS.size) finishLearning(true) else main.post { updateOverlay() }
    }

    private fun finishLearning(ok: Boolean) {
        learnStep = -1
        if (ok) prefs(this).edit().putString("flowLearned", learned.toString()).apply()
        main.post {
            overlayText?.text = if (ok) "✔ Flow learned. Going back to Stickman Studio…" else "Teaching cancelled."
            main.postDelayed({ removeOverlay(); backToApp() }, 1500)
        }
        learnDone?.invoke(JSONObject().put("ok", ok).put("learned", learned).toString())
        learnDone = null
    }

    private fun updateOverlay() {
        val t = when (STEPS.getOrNull(learnStep)) {
            "prompt" -> "Teach Flow 1/4: tap the PROMPT box (where you type)."
            "generate" -> "2/4: type any short prompt, then tap the GENERATE / send button."
            "image" -> "3/4: when the picture is ready, tap the NEW PICTURE. (If Download is already visible, tap Skip.)"
            "download" -> "4/4: tap DOWNLOAD (if a menu opens, choose the size there too)."
            else -> ""
        }
        overlayText?.text = t
    }

    @SuppressLint("SetTextI18n")
    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 20, 20, 40))
            setPadding(28, 20, 28, 20)
        }
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val skip = Button(this).apply { text = "Skip"; setOnClickListener { if (STEPS.getOrNull(learnStep) == "image") nextLearnStep() } }
        val cancel = Button(this).apply { text = "Cancel"; setOnClickListener { finishLearning(false) } }
        row.addView(skip); row.addView(cancel)
        box.addView(tv); box.addView(row)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        try { wm.addView(box, lp); overlay = box; overlayText = tv } catch (e: Exception) { Log.w(TAG, "overlay: ${e.message}") }
    }

    private fun removeOverlay() {
        val o = overlay ?: return
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(o) } catch (_: Exception) {}
        overlay = null; overlayText = null
    }

    // ---------- generation ----------

    fun generate(prompt: String, cb: Callback) {
        if (busy) { cb.failed("Flow is already making a picture"); return }
        busy = true
        worker.execute {
            try { cb.done(runGeneration(prompt, cb)) }
            catch (e: Exception) { Log.w(TAG, "flow failed", e); cb.failed(e.message ?: e.toString()) }
            finally { busy = false; backToApp() }
        }
    }

    private fun runGeneration(prompt: String, cb: Callback): Result {
        val pkg = flowPackage(this)
        if (pkg.isEmpty()) throw IllegalStateException("Pick the Flow app in Auto Pilot settings first")
        val sel = try { JSONObject(prefs(this).getString("flowLearned", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val minWait = prefs(this).getInt("flowMinWait", 20) * 1000L
        val maxWait = prefs(this).getInt("flowMaxWait", 240) * 1000L

        cb.progress("Opening Flow…")
        launch(pkg)
        waitFor(15000) { rootInActiveWindow?.packageName?.toString() == pkg } ?: throw IllegalStateException("Flow did not open (is the screen locked?)")
        Thread.sleep(2500)

        // 1. prompt box
        cb.progress("Typing the prompt…")
        val promptSel = sel.optJSONObject("prompt")
        var box = waitFor(5000) { findBySelector(promptSel, editable = true) ?: findAnyEditable() }
        if (box == null) {
            // Flow opens on the project page: the prompt box comes with its Create button
            findByLabel(OPEN_WORDS)?.let { cb.progress("Opening Flow's Create…"); click(it, null); Thread.sleep(2000) }
            box = waitFor(6000) { findBySelector(promptSel, editable = true) ?: findAnyEditable() }
        }
        if (box == null && promptSel != null) {
            // tap where the box was while teaching, then use whatever got the keyboard focus
            cb.progress("Tapping the prompt box…")
            tapSaved(promptSel)
            Thread.sleep(1500)
            box = waitFor(4000) { focusedInput() ?: findAnyEditable() }
        }
        if (box == null) {
            saveDump("prompt box not found")
            throw IllegalStateException("Could not find Flow's prompt box (is Flow on its prompt screen?). Press \"Copy Flow report\" and send it.")
        }
        box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        Thread.sleep(400)
        val edit = findEditable(box) ?: box
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) }
        var typed = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Thread.sleep(500)
        edit.refresh()
        if (!typed || (edit.text?.length ?: 0) < minOf(20, prompt.length)) {
            // some apps ignore SET_TEXT: paste from the clipboard instead
            main.post { (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("prompt", prompt)) }
            Thread.sleep(400)
            typed = edit.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Thread.sleep(600)
        }
        if (!typed) throw IllegalStateException("Could not type into Flow's prompt box")

        // 2. generate
        cb.progress("Pressing Generate…")
        val before = imageSignature()
        val dlSel = sel.optJSONObject("download")
        val dlBefore = downloadVisible(dlSel)
        val gen = findBySelector(sel.optJSONObject("generate")) ?: findByLabel(GENERATE_WORDS)
        if (gen == null || !click(gen, sel.optJSONObject("generate"))) {
            if (!tapSaved(sel.optJSONObject("generate"))) throw saveDump("generate not found").let { IllegalStateException("Could not find Flow's Generate button. Run \"Teach Flow\" again.") }
        }
        val t0 = System.currentTimeMillis()

        // 3. wait until the picture is ready: nothing is loading and a Download button appeared
        //    (or, when one was already there, the pictures on screen changed)
        Thread.sleep(minWait)
        var calm = 0
        while (System.currentTimeMillis() - t0 < maxWait) {
            val secs = (System.currentTimeMillis() - t0) / 1000
            cb.progress("Flow is drawing… ${secs}s")
            val loading = isLoading()
            val ready = if (!dlBefore) downloadVisible(dlSel) else imageSignature() != before
            calm = if (!loading && ready) calm + 1 else 0
            if (calm >= 2) break
            Thread.sleep(3000)
        }
        Thread.sleep(1500)

        // 4. open the picture (if taught), then download it
        cb.progress("Downloading the picture…")
        if (!downloadVisible(dlSel)) {
            // open the new picture: its tile is named after the prompt, else what was taught
            val tile = promptTile(prompt)
            if (tile != null) { click(tile, null); Thread.sleep(2000) }
            else sel.optJSONObject("image")?.let { s -> findBySelector(s)?.let { click(it, s) } ?: tapSaved(s); Thread.sleep(2000) }
        }
        val since = System.currentTimeMillis() / 1000 - 5
        val dl = findBySelector(sel.optJSONObject("download")) ?: findByLabel(DOWNLOAD_WORDS)
        val clicked = if (dl != null) click(dl, sel.optJSONObject("download")) else tapSaved(sel.optJSONObject("download"))
        if (clicked) {
            Thread.sleep(1500)
            // a size / format menu may open: take the first download-like choice
            findByLabel(MENU_WORDS)?.let { click(it, null) }
            val file = waitFor(45000) { newestImage(since) }
            if (file != null) return file
        }
        // 5. fallback: cut the picture out of a screenshot
        cb.progress("Download not found: taking the picture from the screen…")
        return screenshotPicture() ?: throw saveDump("picture not saved").let { IllegalStateException("The picture could not be saved from Flow. Run \"Teach Flow\" again.") }
    }

    // ---------- finding things on screen ----------

    /** Flow's own window (dialogs, keyboards and toasts can be the "active" window instead). */
    private fun root(): AccessibilityNodeInfo? {
        val pkg = flowPackage(this)
        val rs = roots()
        return rs.firstOrNull { it.packageName?.toString() == pkg } ?: rootInActiveWindow ?: rs.firstOrNull()
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try { windows.forEach { w -> w.root?.let { out.add(it) } } } catch (_: Exception) {}
        rootInActiveWindow?.let { a -> if (out.none { it == a }) out.add(0, a) }
        return out
    }

    private fun editableLike(n: AccessibilityNodeInfo) =
        n.isVisibleToUser && (n.isEditable || (n.className?.toString() ?: "").contains("EditText"))

    private fun downloadVisible(s: JSONObject?): Boolean {
        val desc = s?.optString("desc").orEmpty(); val text = s?.optString("text").orEmpty()
        val r = root() ?: return false
        return findFirst(r) { n ->
            n.isVisibleToUser && listOfNotNull(n.text, n.contentDescription).map { it.toString().trim() }.any {
                it.equals("Download", true) || it.equals("डाउनलोड", true) || (desc.isNotEmpty() && it == desc) || (text.isNotEmpty() && it == text)
            }
        } != null
    }

    private fun promptTile(prompt: String): AccessibilityNodeInfo? {
        val key = prompt.lowercase().replace(Regex("\\s+"), " ").trim().take(24)
        if (key.length < 6) return null
        val r = root() ?: return null
        return findFirst(r) { n ->
            n.isVisibleToUser && n.isClickable && !n.isEditable &&
                (n.contentDescription?.toString()?.lowercase()?.replace(Regex("\\s+"), " ")?.contains(key) == true)
        }
    }

    private fun findAnyEditable(): AccessibilityNodeInfo? {
        val pkg = flowPackage(this)
        for (r in roots()) {
            if (r.packageName?.toString() != pkg) continue
            findFirst(r) { editableLike(it) }?.let { return it }
        }
        return null
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        for (r in roots()) {
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (f.packageName?.toString() == flowPackage(this)) return f
        }
        return null
    }

    /** A short text picture of the screen, kept for "Copy Flow report". */
    private fun saveDump(why: String) {
        val sb = StringBuilder("Flow report: $why\nlearned: ").append(prefs(this).getString("flowLearned", "")).append('\n')
        val dm = resources.displayMetrics
        var lines = 0
        for (r in roots()) {
            sb.append("== window ").append(r.packageName).append('\n')
            walk(r) { n ->
                if (lines > 160 || !n.isVisibleToUser) return@walk
                val t = listOfNotNull(n.text, n.contentDescription).joinToString(" | ").replace('\n', ' ').take(40)
                val flags = (if (n.isEditable) "E" else "") + (if (n.isClickable) "C" else "") + (if (n.isFocused) "F" else "")
                if (t.isEmpty() && flags.isEmpty()) return@walk
                val b = Rect(); n.getBoundsInScreen(b)
                sb.append((n.className ?: "").toString().substringAfterLast('.')).append(' ').append(flags)
                    .append(" @").append("%.2f,%.2f".format(b.exactCenterX() / dm.widthPixels, b.exactCenterY() / dm.heightPixels))
                    .append(' ').append(n.viewIdResourceName?.substringAfterLast('/') ?: "").append(" \"").append(t).append("\"\n")
                lines++
            }
        }
        prefs(this).edit().putString("flowDump", sb.toString().take(12000)).apply()
    }

    private fun describe(n: AccessibilityNodeInfo): JSONObject {
        val r = Rect(); n.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        return JSONObject()
            .put("id", n.viewIdResourceName ?: "")
            .put("text", n.text?.toString()?.take(60) ?: "")
            .put("desc", n.contentDescription?.toString()?.take(60) ?: "")
            .put("cls", n.className?.toString() ?: "")
            .put("x", r.exactCenterX() / dm.widthPixels)
            .put("y", r.exactCenterY() / dm.heightPixels)
    }

    private fun findBySelector(s: JSONObject?, editable: Boolean = false): AccessibilityNodeInfo? {
        if (s == null) return null
        val r = root() ?: return null
        val id = s.optString("id"); val desc = s.optString("desc"); val text = s.optString("text"); val cls = s.optString("cls")
        if (id.isNotEmpty()) r.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
        if (desc.isNotEmpty()) findFirst(r) { it.isVisibleToUser && it.contentDescription?.toString() == desc }?.let { return it }
        if (!editable && text.isNotEmpty()) findFirst(r) { it.isVisibleToUser && it.text?.toString() == text }?.let { return it }
        if (editable) return null
        // same kind of element nearest to where it was
        val dm = resources.displayMetrics
        val x = s.optDouble("x", -1.0) * dm.widthPixels; val y = s.optDouble("y", -1.0) * dm.heightPixels
        if (x < 0 || cls.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null; var bd = Double.MAX_VALUE
        walk(r) { n ->
            if (n.isVisibleToUser && n.className?.toString() == cls && (n.isClickable || n.isEditable)) {
                val b = Rect(); n.getBoundsInScreen(b)
                val d = Math.hypot(b.exactCenterX() - x, b.exactCenterY() - y)
                if (d < bd) { bd = d; best = n }
            }
        }
        return if (bd < dm.widthPixels * 0.08) best else null
    }

    private fun findByLabel(words: List<String>): AccessibilityNodeInfo? {
        val r = root() ?: return null
        for (w in words) {
            val hit = findFirst(r) { n ->
                n.isVisibleToUser && (n.isClickable || n.parent?.isClickable == true) &&
                    listOfNotNull(n.text, n.contentDescription).any { it.toString().trim().equals(w, true) || it.toString().contains(w, true) }
            }
            if (hit != null) return hit
        }
        return null
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? = if (n.isEditable) n else findFirst(n) { it.isEditable }

    private fun findFirst(n: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (test(n)) return n
        for (i in 0 until n.childCount) findFirst(n.getChild(i) ?: continue, test)?.let { return it }
        return null
    }

    private fun walk(n: AccessibilityNodeInfo?, f: (AccessibilityNodeInfo) -> Unit) {
        if (n == null) return
        f(n)
        for (i in 0 until n.childCount) walk(n.getChild(i), f)
    }

    private fun isLoading(): Boolean {
        var busy = false
        walk(root()) { n ->
            val cls = n.className?.toString() ?: ""
            val t = listOfNotNull(n.text, n.contentDescription).joinToString(" ").lowercase()
            if (n.isVisibleToUser && (cls.contains("ProgressBar") || LOADING.containsMatchIn(t))) busy = true
        }
        return busy
    }

    /** A rough fingerprint of the pictures on screen, to notice when a new one appears. */
    private fun imageSignature(): String {
        val sb = StringBuilder()
        walk(root()) { n ->
            val cls = n.className?.toString() ?: ""
            if (n.isVisibleToUser && cls.contains("Image")) { val b = Rect(); n.getBoundsInScreen(b); sb.append(b.toShortString()).append(n.contentDescription ?: "").append(';') }
        }
        return sb.toString()
    }

    private fun click(n: AccessibilityNodeInfo, s: JSONObject?): Boolean {
        var c: AccessibilityNodeInfo? = n
        while (c != null) {
            if (c.isClickable && c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
        }
        val b = Rect(); n.getBoundsInScreen(b)
        return tap(b.exactCenterX(), b.exactCenterY()) || tapSaved(s)
    }

    private fun tapSaved(s: JSONObject?): Boolean {
        if (s == null || !s.has("x")) return false
        val dm = resources.displayMetrics
        return tap((s.optDouble("x") * dm.widthPixels).toFloat(), (s.optDouble("y") * dm.heightPixels).toFloat())
    }

    private fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        val res = AtomicReference<Boolean?>(null)
        main.post {
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { res.set(true) }
                override fun onCancelled(d: GestureDescription?) { res.set(false) }
            }, null)
            if (!ok) res.set(false)
        }
        return waitFor(3000) { res.get() } ?: false
    }

    // ---------- getting the picture ----------

    private fun newestImage(sinceSec: Long): Result? {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE)
        return try {
            contentResolver.query(uri, cols, "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(sinceSec.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
                if (!c.moveToFirst()) return null
                if (c.getLong(2) <= 0) return null   // still being written
                val item = ContentUris.withAppendedId(uri, c.getLong(0))
                Thread.sleep(800)
                val bytes = contentResolver.openInputStream(item)?.use { it.readBytes() } ?: return null
                Result(bytes, c.getString(1) ?: "image/png")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no photo permission: ${e.message}"); null
        }
    }

    private fun screenshotPicture(): Result? {
        if (Build.VERSION.SDK_INT < 30) return null
        var best: Rect? = null
        walk(root()) { n ->
            val cls = n.className?.toString() ?: ""
            if (n.isVisibleToUser && cls.contains("Image")) {
                val b = Rect(); n.getBoundsInScreen(b)
                if (best == null || b.width() * b.height() > best!!.width() * best!!.height()) best = b
            }
        }
        val area = best ?: return null
        if (area.width() < 200 || area.height() < 200) return null
        val out = AtomicReference<Result?>(null); val finished = AtomicReference(false)
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    s.hardwareBuffer.close()
                    if (bmp != null) {
                        val r = Rect(area); r.intersect(0, 0, bmp.width, bmp.height)
                        val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                        val bo = ByteArrayOutputStream(); crop.compress(Bitmap.CompressFormat.PNG, 100, bo)
                        out.set(Result(bo.toByteArray(), "image/png"))
                    }
                } catch (e: Exception) { Log.w(TAG, "screenshot: ${e.message}") }
                finished.set(true)
            }
            override fun onFailure(code: Int) { finished.set(true) }
        })
        waitFor(8000) { if (finished.get()) true else null }
        return out.get()
    }

    // ---------- helpers ----------

    private fun launch(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("The Flow app is not installed")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(i)
    }

    fun backToApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "back: ${e.message}") }
    }

    /** Opens Stickman Studio for a scheduled run (a bound accessibility service may start activities). */
    fun openForAutoPilot() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_AUTOPILOT, true)
        try { startActivity(i) } catch (e: Exception) { Log.w(TAG, "autopilot open: ${e.message}") }
    }

    private fun <T> waitFor(ms: Long, f: () -> T?): T? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            try { f()?.let { return it } } catch (_: Exception) {}
            Thread.sleep(400)
        }
        return null
    }

    companion object {
        private const val TAG = "SSAPP"
        @Volatile var instance: FlowService? = null
        private val STEPS = listOf("prompt", "generate", "image", "download")
        private val OPEN_WORDS = listOf("Create", "New project", "New", "बनाएं")
        private val GENERATE_WORDS = listOf("Generate", "Create", "Send", "Submit", "Make", "Run", "बनाएं", "भेजें")
        private val DOWNLOAD_WORDS = listOf("Download", "Save to device", "Save image", "Save", "डाउनलोड")
        private val MENU_WORDS = listOf("Original size", "Full size", "Download image", "2K", "1K", "PNG", "Download")
        private val LOADING = Regex("generating|creating|loading|rendering|in progress|\\b\\d{1,2}%")

        fun prefs(c: Context) = c.getSharedPreferences("autopilot", Context.MODE_PRIVATE)
        fun flowPackage(c: Context) = prefs(c).getString("flowPkg", "") ?: ""

        /** Launchable apps, the ones that look like Flow first. */
        fun apps(c: Context): String {
            val pm = c.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }.filter { it.first != c.packageName }
                .sortedWith(compareBy({ !(it.second.contains("flow", true) || it.first.contains("flow", true)) }, { it.second.lowercase() }))
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("pkg", it.first).put("label", it.second)) }
            return arr.toString()
        }
    }
}
