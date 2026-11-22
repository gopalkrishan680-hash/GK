package com.gopalkrishan.stickmanstudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.ClipDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import android.app.KeyguardManager
import android.provider.Settings
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.security.MessageDigest
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Stickman Studio runs the same page as the browser version (assets/index.html) inside a WebView,
 * served from https://appassets.androidplatform.net so storage, WebCodecs and the clipboard work.
 * Native code fills the gaps a WebView has: picking files, saving videos to the gallery,
 * reading a copied image, receiving images/audio shared from other apps and keeping the screen on.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val pendingShares = ArrayList<Uri>()
    private var pendingLink: String? = null
    private var lastClipStamp = -1L
    private var pageReady = false
    private var pendingAuto: String? = null
    private var pendingTab: String? = null
    private var tokenWaiter: ((String?, String?) -> Unit)? = null

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        js("window.ssPermsDone && ssPermsDone(" + JSONObject.quote(JSONObject(r.mapValues { it.value }).toString()) + ")")
    }

    // Google sign-in for YouTube: the consent screen comes back here
    private val googleAuth = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val w = tokenWaiter; tokenWaiter = null
        try {
            val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
            w?.invoke(r.accessToken, if (r.accessToken == null) "No access was given" else null)
        } catch (e: Exception) { w?.invoke(null, "Google sign-in cancelled (${e.message})") }
    }

    private var saveStream: OutputStream? = null
    private var saveUri: Uri? = null
    private var saveLabel = ""
    private var saveMime = ""
    @Volatile private var lastSaved: Uri? = null
    @Volatile private var lastSavedMime = ""

    // The page asks for the microphone (voice recordings); ask Android, then answer the page.
    private var pendingMic: PermissionRequest? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingMic ?: return@registerForActivityResult
        pendingMic = null
        if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val uris = ArrayList<Uri>()
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
            } else {
                data.data?.let { uris.add(it) }
            }
        }
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The in-app voice makers were removed: free the space of voice models downloaded by older versions
        Thread { for (d in listOf("kokoro", "indic")) try { java.io.File(filesDir, d).deleteRecursively() } catch (_: Exception) {} }.start()
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                openExternal(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                flushShares()
                flushLink()
                flushAuto()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val types = params.acceptTypes
                    .flatMap { it.split(",") }
                    .map { it.trim() }
                    .filter { it.contains("/") }
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (types.size == 1) types[0] else "*/*"
                    if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    pickFiles.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (!request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        request.deny()
                    } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMic?.deny()
                        pendingMic = request
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "${m.messageLevel()} ${m.message()} @${m.lineNumber()}")
                return true
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) {
                    web.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val selfTest = intent?.getBooleanExtra("selftest", false) == true
        web.loadUrl("https://$HOST/assets/index.html" + if (selfTest) "#selftest" else "")
        handleIntent(intent)
    }

    /** Wake the screen and show over a swipe lock screen for a scheduled run. */
    private fun wakeForAutoPilot() {
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null) } catch (_: Exception) {}
    }

    private fun flushAuto() {
        if (!pageReady) return
        pendingTab?.let { pendingTab = null; js("window.ssOpenTab && ssOpenTab(" + JSONObject.quote(it) + ")") }
        pendingAuto?.let { pendingAuto = null; js("window.ssAutoPilotStart && ssAutoPilotStart(" + JSONObject.quote(it) + ")") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        try { saveStream?.close() } catch (_: Exception) {}
        web.destroy()
        super.onDestroy()
    }

    // ---------- files shared into the app ----------

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        i.getStringExtra(EXTRA_OPEN_TAB)?.let { pendingTab = it; i.removeExtra(EXTRA_OPEN_TAB) }
        if (i.getBooleanExtra(EXTRA_AUTOPILOT, false)) { i.removeExtra(EXTRA_AUTOPILOT); pendingAuto = "alarm"; wakeForAutoPilot() }
        flushAuto()
        // stickmanstudio://connect?url=https://….trycloudflare.com from the voice notebook's button
        if (i.action == Intent.ACTION_VIEW && i.data?.scheme == "stickmanstudio") {
            pendingLink = i.data?.getQueryParameter("url") ?: return
            flushLink()
            return
        }
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamOf(i)).ifEmpty { clipUris(i) }
            Intent.ACTION_SEND_MULTIPLE -> multiStreams(i).ifEmpty { clipUris(i) }
            else -> return
        }
        pendingShares.addAll(uris)
        flushShares()
    }

    /** The page checks the link and asks the user before connecting. */
    private fun flushLink() {
        val u = pendingLink ?: return
        if (!pageReady) return
        pendingLink = null
        js("window.ssConnectLink && window.ssConnectLink(" + org.json.JSONObject.quote(u) + ")")
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun multiStreams(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)) ?: emptyList()

    private fun clipUris(i: Intent): List<Uri> {
        val clip = i.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }

    /** Streams each shared file to the page in base64 chunks, then tells it the name and type. */
    private fun flushShares() {
        if (!pageReady || pendingShares.isEmpty()) return
        val list = ArrayList(pendingShares)
        pendingShares.clear()
        Thread {
            for ((n, uri) in list.withIndex()) {
                try {
                    val mime = contentResolver.getType(uri) ?: guessMime(uri)
                    val name = displayName(uri)
                    val id = "s${System.currentTimeMillis()}_$n"
                    contentResolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(768 * 1024)
                        while (true) {
                            val r = readFully(input, buf)
                            if (r <= 0) break
                            val b64 = Base64.encodeToString(buf, 0, r, Base64.NO_WRAP)
                            js("window.receiveSharedChunk && receiveSharedChunk('$id','$b64')")
                            if (r < buf.size) break
                        }
                    } ?: throw IllegalStateException("cannot open file")
                    js("window.receiveSharedEnd && receiveSharedEnd('$id', ${JSONObject.quote(name)}, ${JSONObject.quote(mime)})")
                } catch (e: Exception) {
                    toast("Could not read the shared file: ${e.message}")
                }
            }
        }.start()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val v = c.getString(0)
                    if (!v.isNullOrBlank()) return v
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "shared-file"
    }

    private fun guessMime(uri: Uri): String {
        val n = displayName(uri).lowercase()
        return when {
            n.endsWith(".png") -> "image/png"
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".m4a") -> "audio/mp4"
            else -> "application/octet-stream"
        }
    }

    private fun js(code: String) {
        runOnUiThread { web.evaluateJavascript(code, null) }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast("No app can open $uri")
        }
    }

    // ---------- called from the page ----------

    inner class Bridge {

        /** Start saving a file: videos go to Movies/Stickman Studio, everything else to Download/Stickman Studio. */
        @JavascriptInterface
        fun saveStart(name: String, mime: String): Boolean {
            try { saveStream?.close() } catch (_: Exception) {}
            val isVideo = mime.startsWith("video/")
            val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Stickman Studio")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = contentResolver.insert(collection, values) ?: return false
            saveUri = uri
            saveMime = mime
            saveStream = contentResolver.openOutputStream(uri)
            saveLabel = "$folder/Stickman Studio/$name"
            return saveStream != null
        }

        @JavascriptInterface
        fun saveChunk(b64: String): Boolean {
            val s = saveStream ?: return false
            s.write(Base64.decode(b64, Base64.DEFAULT))
            return true
        }

        @JavascriptInterface
        fun saveEnd(): String {
            val uri = saveUri ?: return ""
            try { saveStream?.close() } catch (_: Exception) {}
            saveStream = null
            contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            saveUri = null
            lastSaved = uri
            lastSavedMime = saveMime
            return saveLabel
        }

        /** The copied image, as "mime;base64", or "" when the clipboard holds no image. */
        @JavascriptInterface
        fun clipboardImage(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                val mime = try { contentResolver.getType(uri) } catch (_: Exception) { null } ?: continue
                if (!mime.startsWith("image/")) continue
                val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null } ?: continue
                return mime + ";" + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            return ""
        }

        /** A voice server link that was newly copied (each copy is read once), or "". */
        @JavascriptInterface
        fun clipboardText(): String {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val desc = cm.primaryClipDescription ?: return ""
            if (!desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !desc.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)) return ""
            val stamp = if (Build.VERSION.SDK_INT >= 26) desc.timestamp else 0L
            if (Build.VERSION.SDK_INT >= 26 && stamp == lastClipStamp) return ""
            lastClipStamp = stamp
            val text = try { cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() } catch (_: Exception) { null } ?: return ""
            return Regex("https://[a-z0-9-]+\\.trycloudflare\\.com").find(text)?.value ?: ""
        }

        @JavascriptInterface
        fun copyText(text: String) {
            runOnUiThread {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Stickman Studio prompt", text))
            }
        }

        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /** Opens the ChatGPT, Gemini or Claude app (or its website when the app is not installed). */
        @JavascriptInterface
        fun openApp(which: String) {
            val (pkg, url) = when (which) {
                "gemini" -> "com.google.android.apps.bard" to "https://gemini.google.com/app"
                "claude" -> "com.anthropic.claude" to "https://claude.ai/new"
                else -> "com.openai.chatgpt" to "https://chatgpt.com/"
            }
            runOnUiThread {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    try { startActivity(launch); return@runOnUiThread } catch (_: Exception) {}
                }
                openExternal(Uri.parse(url))
            }
        }

        // ---------- Auto Pilot ----------

        @JavascriptInterface
        fun flowStatus(): String {
            val p = FlowService.prefs(this@MainActivity)
            val pkg = FlowService.flowPackage(this@MainActivity)
            val label = try { if (pkg.isEmpty()) "" else packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
            val photoPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            return JSONObject()
                .put("service", FlowService.instance != null)
                .put("pkg", pkg).put("label", label)
                .put("learned", (p.getString("flowLearned", "") ?: "").length > 5)
                .put("photos", checkSelfPermission(photoPerm) == PackageManager.PERMISSION_GRANTED)
                .put("notify", Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                .put("exact", Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                .put("daily", p.getBoolean("daily", false)).put("hour", p.getInt("hour", 13)).put("minute", p.getInt("minute", 0))
                .put("nextRun", p.getLong("nextRun", 0))
                .put("minWait", p.getInt("flowMinWait", 20)).put("maxWait", p.getInt("flowMaxWait", 240))
                .toString()
        }

        @JavascriptInterface
        fun flowDebug(): String = FlowService.prefs(this@MainActivity).let { (it.getString("flowDump", "") ?: "") + "\n\n" + (it.getString("flowDumpComposer", "") ?: "") + "\n\n" + (it.getString("flowDumpAspect", "") ?: "") }.trim()

        @JavascriptInterface
        fun flowApps(): String = FlowService.apps(this@MainActivity)

        @JavascriptInterface
        fun flowSetApp(pkg: String) {
            FlowService.prefs(this@MainActivity).edit().putString("flowPkg", pkg).remove("flowLearned").apply()
        }

        @JavascriptInterface
        fun flowSetWaits(min: Int, max: Int) {
            FlowService.prefs(this@MainActivity).edit().putInt("flowMinWait", min.coerceIn(5, 300)).putInt("flowMaxWait", max.coerceIn(30, 900)).apply()
        }

        @JavascriptInterface
        fun openAccessibility() { runOnUiThread { try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {} } }

        @JavascriptInterface
        fun openExactAlarm() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { toast("Allow \"Alarms & reminders\" for Stickman Studio in Settings") }
            }
        }

        @JavascriptInterface
        fun requestPerms() {
            val list = ArrayList<String>()
            list.add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
            runOnUiThread { perms.launch(list.toTypedArray()) }
        }

        @JavascriptInterface
        fun flowLearn(): Boolean {
            val svc = FlowService.instance ?: return false
            runOnUiThread { svc.startLearning { res -> js("window.ssFlowLearned && ssFlowLearned(" + JSONObject.quote(res) + ")") } }
            return true
        }

        /** Makes one picture in Flow; the page gets ssFlowProgress / ssFlowChunk / ssFlowEnd / ssFlowFail. */
        @JavascriptInterface
        fun flowGenerate(id: String, prompt: String): Boolean {
            val svc = FlowService.instance ?: return false
            svc.generate(prompt, object : FlowService.Callback {
                override fun progress(msg: String) { js("window.ssFlowProgress && ssFlowProgress('$id', ${JSONObject.quote(msg)})") }
                override fun failed(msg: String) { js("window.ssFlowFail && ssFlowFail('$id', ${JSONObject.quote(msg)})") }
                override fun done(r: FlowService.Result) {
                    val CH = 768 * 1024
                    var o = 0
                    while (o < r.bytes.size) {
                        val n = minOf(CH, r.bytes.size - o)
                        js("window.ssFlowChunk && ssFlowChunk('$id','${Base64.encodeToString(r.bytes, o, n, Base64.NO_WRAP)}')")
                        o += n
                    }
                    js("window.ssFlowEnd && ssFlowEnd('$id', ${JSONObject.quote(r.mime)})")
                }
            })
            return true
        }

        @JavascriptInterface
        fun notify(title: String, text: String) { AutoPilot.notify(this@MainActivity, AutoPilot.NOTE_STATUS, title, text) }

        @JavascriptInterface
        fun setSchedule(on: Boolean, hour: Int, minute: Int): String {
            FlowService.prefs(this@MainActivity).edit().putBoolean("daily", on).putInt("hour", hour).putInt("minute", minute).apply()
            AutoPilot.schedule(this@MainActivity)
            return flowStatus()
        }

        /** The SHA-1 of the app's signing key, needed for the Android OAuth client in Google Cloud. */
        @JavascriptInterface
        fun appSha1(): String = try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo!!.apkContentsSigners[0].toByteArray()
            MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun packageId(): String = packageName

        /** Sign in to YouTube (once); later calls get a fresh token silently. Answers with ssYtAuth(ok, msg). */
        @JavascriptInterface
        fun ytConnect() {
            withToken { t, err -> js("window.ssYtAuth && ssYtAuth(${t != null}, ${JSONObject.quote(err ?: "")})") }
        }

        /** Uploads the video saved last (Movies/Stickman Studio). Answers with ssYtProgress / ssYtDone / ssYtFail. */
        @JavascriptInterface
        fun ytUpload(meta: String, thumbB64: String) {
            val video = lastSaved
            if (video == null) { js("window.ssYtFail && ssYtFail('Export the video first')"); return }
            val thumb = if (thumbB64.isEmpty()) null else Base64.decode(thumbB64, Base64.DEFAULT)
            withToken { token, err ->
                if (token == null) { js("window.ssYtFail && ssYtFail(${JSONObject.quote(err ?: "Not signed in")})"); return@withToken }
                Thread {
                    try {
                        val out = YouTube.upload(this@MainActivity, token, video, JSONObject(meta), thumb, object : YouTube.Progress {
                            override fun update(msg: String, frac: Double) { js("window.ssYtProgress && ssYtProgress(${JSONObject.quote(msg)}, $frac)") }
                        })
                        js("window.ssYtDone && ssYtDone(${JSONObject.quote(out.toString())})")
                    } catch (e: Exception) {
                        Log.w(TAG, "upload", e)
                        js("window.ssYtFail && ssYtFail(${JSONObject.quote(e.message ?: e.toString())})")
                    }
                }.start()
            }
        }

        /** Share the last exported video (WhatsApp, YouTube, Drive...). */
        @JavascriptInterface
        fun shareLast(): Boolean {
            val uri = lastSaved ?: return false
            val mime = lastSavedMime.ifBlank { "video/mp4" }
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share video"))
            }
            return true
        }
    }

    private fun withToken(cb: (String?, String?) -> Unit) {
        val req = AuthorizationRequest.builder().setRequestedScopes(YouTube.SCOPES.map { Scope(it) }).build()
        runOnUiThread {
            Identity.getAuthorizationClient(this).authorize(req)
                .addOnSuccessListener { r: AuthorizationResult ->
                    val pi = r.pendingIntent
                    if (r.hasResolution() && pi != null) {
                        tokenWaiter = cb
                        try { googleAuth.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
                        catch (e: Exception) { tokenWaiter = null; cb(null, "Could not open Google sign-in: ${e.message}") }
                    } else cb(r.accessToken, if (r.accessToken == null) "No access token" else null)
                }
                .addOnFailureListener { e ->
                    val code = (e as? ApiException)?.statusCode
                    cb(null, if (code == 10) "Google says this app is not registered (DEVELOPER_ERROR). Add an Android OAuth client with package $packageName and the SHA-1 shown in Auto Pilot, in the same Google Cloud project where YouTube Data API v3 is on."
                    else "Google sign-in failed: ${e.message}")
                }
        }
    }

    companion object {
        const val EXTRA_AUTOPILOT = "autopilot"
        const val EXTRA_OPEN_TAB = "openTab"
        private const val HOST = "appassets.androidplatform.net"
        private const val TAG = "SSAPP"
    }
}
