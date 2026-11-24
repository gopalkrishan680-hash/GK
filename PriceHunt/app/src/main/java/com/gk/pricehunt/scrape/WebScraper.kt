package com.gk.pricehunt.scrape

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Loads pages in an off-screen WebView (so JavaScript-rendered stores work, just like a
 * real browser) and runs an extractor script on them. No server needed – everything
 * happens on the phone.
 */
class WebScraper(private val context: Context) {

    private val searchJs by lazy { context.assets.open("search.js").bufferedReader().readText() }
    private val productJs by lazy { context.assets.open("product.js").bufferedReader().readText() }
    private val gate = Semaphore(3)

    /** Polls the page until [isComplete] says the extracted JSON is good enough (or timeout). */
    suspend fun scrape(
        url: String,
        extractorCall: String,
        timeoutMs: Long = 25_000,
        isComplete: (JSONObject, Int) -> Boolean,
    ): Pair<JSONObject?, String?> = gate.withPermit {
        withContext(Dispatchers.Main) {
            val wv = createWebView()
            var last: JSONObject? = null
            var finalUrl: String? = url
            try {
                wv.loadUrl(url)
                withTimeoutOrNull(timeoutMs) {
                    var poll = 0
                    delay(2_000)
                    while (true) {
                        poll++
                        wv.evaluateJavascript("window.scrollBy(0, 1400);", null)
                        val lib = if (extractorCall.startsWith("__phSearch")) searchJs else productJs
                        val raw = wv.eval("$lib\n;$extractorCall")
                        val json = raw?.let { decode(it) }
                        if (json != null) {
                            last = json
                            finalUrl = wv.url
                            if (isComplete(json, poll)) break
                        }
                        delay(1_200)
                    }
                }
                finalUrl = wv.url ?: finalUrl
            } finally {
                wv.stopLoading()
                wv.destroy()
            }
            last to finalUrl
        }
    }

    suspend fun search(storeId: String, linkRegex: String, url: String): Pair<JSONObject?, String?> {
        val cfg = JSONObject().put("store", storeId).put("linkRe", linkRegex)
        var lastCount = -1
        var stable = 0
        return scrape(url, "__phSearch($cfg)") { json, poll ->
            val n = json.optJSONArray("items")?.length() ?: 0
            if (json.optBoolean("blocked")) return@scrape poll >= 3
            if (n > 0 && n == lastCount) stable++ else stable = 0
            lastCount = n
            // results found and stopped growing, or nothing after a long wait
            (n > 0 && (stable >= 1 || n >= 30)) || poll >= 14
        }
    }

    suspend fun product(url: String): Pair<JSONObject?, String?> {
        var lastPrice = -1.0
        return scrape(url, "__phProduct()", timeoutMs = 22_000) { json, poll ->
            val p = json.optDouble("price", Double.NaN)
            val done = !p.isNaN() && p > 0 && p == lastPrice
            if (!p.isNaN()) lastPrice = p
            done || json.optBoolean("blocked") && poll >= 3 || poll >= 12
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(context.applicationContext)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            userAgentString = DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                // never let a store page bounce us into its app / intent:// links
                return scheme != "http" && scheme != "https"
            }
        }
        // give the page a real viewport so lazy loading / layout works off-screen
        val w = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)
        wv.measure(w, h)
        wv.layout(0, 0, 1280, 2400)
        return wv
    }

    private suspend fun WebView.eval(js: String): String? = suspendCancellableCoroutine { cont ->
        evaluateJavascript(js) { if (cont.isActive) cont.resume(it) }
    }

    private fun decode(raw: String): JSONObject? = runCatching {
        // evaluateJavascript returns the JSON-encoded return value: a quoted string here
        val s = JSONTokener(raw).nextValue() as? String ?: return null
        JSONObject(s)
    }.getOrNull()

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
