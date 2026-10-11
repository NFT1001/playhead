package app.playhead.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/** What a tab's WebView needs from the browser around it. MainActivity implements this. */
interface BrowserHost {
    val activity: Activity
    val prefs: Prefs
    val debuggable: Boolean
    fun tabChanged(tab: Tab)
    fun pageFinished(tab: Tab)
    fun createWindow(opener: Tab, resultMsg: Message): Boolean
    fun showCustom(view: View, callback: WebChromeClient.CustomViewCallback)
    fun hideCustom()
    fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean
    fun sitePermission(request: PermissionRequest)
    fun geolocation(origin: String, callback: GeolocationPermissions.Callback)
    fun linkMenu(web: WebView, tab: Tab): Boolean
    fun renderGone(tab: Tab)
}

/** The Better YouTube script plus what the device can do, built once and shared by every tab. */
object ScriptHolder {
    private var cached: String? = null
    private var loaded = false

    fun get(context: Context): String? {
        if (loaded) return cached
        loaded = true
        val source = try {
            context.assets.open("betteryt.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return null
        }
        val device = DeviceInfo.collect(context).toString()
        // The script was written for the top frame only: in subframes mark it as loaded so it exits at once.
        cached = "if (window.top !== window.self) { window.__betterYTLoaded = true; } " +
            "else { window.__BYT_NATIVE = $device; }\n" + source
        return cached
    }
}

class WebViewFactory(private val host: BrowserHost) {

    private val activity get() = host.activity
    private val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** A desktop Chrome user agent that tracks the installed WebView version, so sites serve their desktop layout. */
    private val desktopUa: String by lazy {
        val version = WebViewCompat.getCurrentWebViewPackage(activity)?.versionName ?: "120.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${version.substringBefore('.')}.0.0.0 Safari/537.36"
    }

    init {
        CookieManager.getInstance().setAcceptCookie(true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(web: WebView) {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Lay the page out at the real window width, so desktop layouts fit the screen.
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = if (host.prefs.desktopSite) desktopUa else null
        }
    }

    fun create(tab: Tab): WebView {
        val web = WebView(activity)
        web.setBackgroundColor(Color.WHITE)
        applySettings(web)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        if (host.debuggable) WebView.setWebContentsDebuggingEnabled(true)
        installScript(web)
        web.webViewClient = Client(tab)
        web.webChromeClient = Chrome(tab)
        web.setDownloadListener { url, userAgent, disposition, mime, _ -> download(url, userAgent, disposition, mime) }
        web.setOnLongClickListener { host.linkMenu(web, tab) }
        web.overScrollMode = View.OVER_SCROLL_NEVER
        // Keep the page's renderer at high priority so Android is less likely to kill it on a low-memory tablet.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        return web
    }

    private fun installScript(web: WebView) {
        // The script tells the app which of its settings the network layer should follow.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "BYTNative", ORIGINS) { _, message, _, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                try {
                    val data = message.data ?: return@addWebMessageListener
                    Blocker.enabled = JSONObject(data).optBoolean("adRequests", true)
                } catch (_: Exception) {
                    // Ignore a malformed message.
                }
            }
        }
        val src = ScriptHolder.get(activity) ?: return
        if (docStart) WebViewCompat.addDocumentStartJavaScript(web, src, ORIGINS)
    }

    // ---- downloads ----

    fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (!url.startsWith("http")) {
            Toast.makeText(activity, "This kind of download is not supported yet", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            val req = DownloadManager.Request(Uri.parse(url))
            if (mime != null) req.setMimeType(mime)
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(activity, "Downloading $name", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "Can't download this file", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- clients ----

    private inner class Client(val tab: Tab) : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!request.isForMainFrame && Blocker.enabled && Blocker.shouldBlock(request.url)) {
                Blocker.blocked.incrementAndGet()
                if (host.debuggable) Log.d(TAG, "blocked ${request.url.host}${request.url.path}")
                return Blocker.emptyResponse()
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            if (scheme == "https" || scheme == "http") return false
            if (request.hasGesture()) openExternal(request.url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.loading = true
            tab.progress = 0
            host.tabChanged(tab)
            // Old WebViews cannot inject at document start; fall back to injecting as soon as the page begins.
            if (!docStart) {
                val h = Uri.parse(url).host
                if (h == "www.youtube.com" || h == "m.youtube.com") {
                    ScriptHolder.get(activity)?.let { view.evaluateJavascript(it, null) }
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.url = url
            tab.loading = false
            view.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
            host.tabChanged(tab)
            host.pageFinished(tab)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            host.tabChanged(tab)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showErrorPage(view, request.url.toString(), error.description.toString())
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            host.renderGone(tab)
            return true
        }
    }

    private inner class Chrome(val tab: Tab) : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            host.tabChanged(tab)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) {
                tab.title = title
                host.tabChanged(tab)
            }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Only a real tap may open a new tab; pages cannot spawn popups on their own.
            if (!isUserGesture) return false
            return host.createWindow(tab, resultMsg)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.showCustom(view, callback)

        override fun onHideCustomView() = host.hideCustom()

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = host.chooseFile(filePathCallback, fileChooserParams)

        override fun onPermissionRequest(request: PermissionRequest) = host.sitePermission(request)

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            host.geolocation(origin, callback)

        // Avoids a known crash on some WebView versions when no poster is supplied.
        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun openExternal(uri: Uri) {
        val text = uri.toString()
        // This is a YouTube browser: never bounce the user into the YouTube app.
        if (uri.scheme == "vnd.youtube" || (uri.scheme == "intent" && text.contains("com.google.android.youtube"))) return
        try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(text, Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            // Nothing can open it: ignore.
        }
    }

    private fun showErrorPage(view: WebView, url: String, reason: String) {
        val u = TextUtils.htmlEncode(url)
        val r = TextUtils.htmlEncode(reason)
        val html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>Can't open page</title><style>" +
            "body{font-family:sans-serif;margin:0;padding:12vh 8vw;color:#0f0f0f;background:#fff}" +
            "h1{font-size:22px;font-weight:600}p{color:#555;line-height:1.5;word-break:break-all}" +
            "a{display:inline-block;margin-top:18px;padding:10px 22px;border-radius:20px;background:#0f0f0f;" +
            "color:#fff;text-decoration:none}" +
            "@media(prefers-color-scheme:dark){body{background:#0f0f0f;color:#f1f1f1}p{color:#aaa}" +
            "a{background:#f1f1f1;color:#0f0f0f}}</style></head><body>" +
            "<h1>Can't open this page</h1><p>$u</p><p>$r</p><a href='$u'>Try again</a></body></html>"
        view.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    private companion object {
        const val TAG = "PLH"
        val ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com")
    }
}
