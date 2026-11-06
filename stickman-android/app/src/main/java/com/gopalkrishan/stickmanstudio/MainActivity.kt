package com.gopalkrishan.stickmanstudio

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
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
    private var pageReady = false

    private var saveStream: OutputStream? = null
    private var saveUri: Uri? = null
    private var saveLabel = ""
    private var saveMime = ""
    @Volatile private var lastSaved: Uri? = null
    @Volatile private var lastSavedMime = ""

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
        val uris = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamOf(i)).ifEmpty { clipUris(i) }
            Intent.ACTION_SEND_MULTIPLE -> multiStreams(i).ifEmpty { clipUris(i) }
            else -> return
        }
        pendingShares.addAll(uris)
        flushShares()
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

        /** Opens the ChatGPT or Gemini app (or its website when the app is not installed). */
        @JavascriptInterface
        fun openApp(which: String) {
            val (pkg, url) = if (which == "gemini") "com.google.android.apps.bard" to "https://gemini.google.com/app"
            else "com.openai.chatgpt" to "https://chatgpt.com/"
            runOnUiThread {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    try { startActivity(launch); return@runOnUiThread } catch (_: Exception) {}
                }
                openExternal(Uri.parse(url))
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

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val TAG = "SSAPP"
    }
}
