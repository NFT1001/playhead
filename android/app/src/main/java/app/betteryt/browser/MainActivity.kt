package app.betteryt.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * M1: a single-tab browser shell. One WebView, a small toolbar, the Better YouTube script injected at
 * document start on youtube.com, and working fullscreen video.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var videoLayer: FrameLayout
    private lateinit var urlBox: EditText
    private lateinit var loadBar: ProgressBar
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var reloadBtn: ImageButton

    private val chrome = Chrome()
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var loading = false
    private var lastUrl = HOME
    private var scriptSource: String? = null
    private var scriptViaDocumentStart = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> chrome.onHideCustomView()
                web.canGoBack() -> web.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        configureWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)
        val start = savedInstanceState?.getString(STATE_URL) ?: urlFromIntent(intent) ?: HOME
        web.loadUrl(start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let { web.loadUrl(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_URL, lastUrl)
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---- UI ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rippleRes(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return tv.resourceId
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundResource(rippleRes())
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        backBtn = iconButton(R.drawable.ic_back, "Back") { if (web.canGoBack()) web.goBack() }
        forwardBtn = iconButton(R.drawable.ic_forward, "Forward") { if (web.canGoForward()) web.goForward() }
        reloadBtn = iconButton(R.drawable.ic_refresh, "Reload") { if (loading) web.stopLoading() else web.reload() }
        val homeBtn = iconButton(R.drawable.ic_home, "Home") { web.loadUrl(HOME) }

        urlBox = EditText(this).apply {
            setSingleLine(true)
            hint = "Search YouTube or type a web address"
            textSize = 15f
            setTextColor(Color.rgb(15, 15, 15))
            setHintTextColor(Color.rgb(110, 110, 110))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.rgb(241, 241, 241))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginStart = dp(6)
                marginEnd = dp(6)
            }
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    go()
                    true
                } else {
                    false
                }
            }
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(backBtn)
            addView(forwardBtn)
            addView(reloadBtn)
            addView(urlBox)
            addView(homeBtn)
        }

        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(Color.rgb(15, 15, 15))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(toolbar)
            addView(loadBar)
            addView(web)
        }

        videoLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        frame = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(videoLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(frame)

        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, frame).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        updateNav()
    }

    private fun setSystemBars(visible: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun updateNav() {
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (web.canGoForward()) 1f else 0.35f
        reloadBtn.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        reloadBtn.contentDescription = if (loading) "Stop" else "Reload"
    }

    private fun showUrl(url: String?) {
        if (url == null) return
        lastUrl = url
        if (!urlBox.hasFocus()) urlBox.setText(url)
    }

    private fun go() {
        web.loadUrl(resolve(urlBox.text.toString()))
        urlBox.clearFocus()
        WindowCompat.getInsetsController(window, urlBox).hide(WindowInsetsCompat.Type.ime())
        web.requestFocus()
    }

    private fun resolve(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        if (t.startsWith("https://")) return t
        if (t.startsWith("http://")) return "https://" + t.removePrefix("http://")
        val looksLikeAddress = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))
        return if (looksLikeAddress) "https://$t" else SEARCH + Uri.encode(t)
    }

    private fun urlFromIntent(intent: Intent?): String? {
        val data = intent?.data ?: return null
        return if (data.scheme == "https") data.toString() else null
    }

    // ---- WebView ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Lay the page out at the real window width so the desktop YouTube layout fits the tablet.
            useWideViewPort = false
            loadWithOverviewMode = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = desktopUserAgent()
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        installScript()
        web.webViewClient = Client()
        web.webChromeClient = chrome
    }

    /** A desktop Chrome user agent that tracks the installed WebView version, so YouTube serves its desktop site. */
    private fun desktopUserAgent(): String {
        val version = WebViewCompat.getCurrentWebViewPackage(this)?.versionName ?: "120.0.0.0"
        val major = version.substringBefore('.')
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }

    private fun installScript() {
        val source = try {
            assets.open("betteryt.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return
        }
        // The script was written for the top frame only. In subframes, mark it as already loaded so it exits at once.
        val guard = "if (window.top !== window.self) { window.__betterYTLoaded = true; }\n"
        scriptSource = guard + source
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, scriptSource!!, YOUTUBE_ORIGINS)
            scriptViaDocumentStart = true
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            return !(scheme == "https" || scheme == "http")
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loading = true
            loadBar.visibility = View.VISIBLE
            showUrl(url)
            updateNav()
            // Old WebViews cannot inject at document start; fall back to injecting as soon as the page begins.
            val host = Uri.parse(url).host
            if (!scriptViaDocumentStart && scriptSource != null && (host == "www.youtube.com" || host == "m.youtube.com")) {
                view.evaluateJavascript(scriptSource!!, null)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false
            loadBar.visibility = View.GONE
            showUrl(url)
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            showUrl(url)
            updateNav()
        }

        // The tablet has little memory: if the WebView's process is killed, rebuild the view and reload the page.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recreate()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loadBar.progress = newProgress
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            videoLayer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            videoLayer.visibility = View.VISIBLE
            root.visibility = View.INVISIBLE
            setSystemBars(false)
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            videoLayer.removeView(view)
            videoLayer.visibility = View.GONE
            root.visibility = View.VISIBLE
            customView = null
            customCallback?.onCustomViewHidden()
            customCallback = null
            setSystemBars(true)
        }

        // Avoids a known crash on some WebView versions when no poster is supplied.
        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val HOME = "https://www.youtube.com/"
        const val SEARCH = "https://www.youtube.com/results?search_query="
        const val STATE_URL = "url"
        val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com")
    }
}
