package app.playhead.browser

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.Executors

/**
 * Playhead: a YouTube-first browser. This class is the browser window: toolbar, tab switcher, menus and the
 * hand-off between tabs. Tabs and their WebViews are managed by [TabManager].
 */
class MainActivity : ComponentActivity(), BrowserHost, TabManager.Callbacks {

    override val activity: Activity get() = this
    override val prefs: Prefs by lazy { Prefs(this) }
    override val debuggable: Boolean by lazy { (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var tabs: TabManager
    private lateinit var factory: WebViewFactory
    private lateinit var bookmarks: Bookmarks
    private lateinit var history: History

    private lateinit var frame: FrameLayout
    private lateinit var webHost: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var panelLayer: FrameLayout
    private lateinit var videoLayer: FrameLayout
    private lateinit var startPage: StartPage
    private lateinit var urlBox: EditText
    private lateinit var loadBar: ProgressBar
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var reloadBtn: ImageButton
    private lateinit var tabCount: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findBox: EditText
    private lateinit var findCount: TextView

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var panel: View? = null
    private var switcher: TabSwitcher? = null

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingResources: Array<String> = emptyArray()
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val request = pendingPermission
            pendingPermission = null
            if (request != null) {
                if (grants.values.all { it }) request.grant(pendingResources) else request.deny()
            }
        }
    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingGeoCallback?.invoke(pendingGeoOrigin ?: "", granted, false)
        pendingGeoCallback = null
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (!handleBack()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    // ---- lifecycle ----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bookmarks = Bookmarks(filesDir, io)
        history = History(filesDir, io)
        factory = WebViewFactory(this)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // One recent tab stays alive in the background for instant switching, unless memory is very tight.
        tabs = TabManager(this, io, if (am.isLowRamDevice) 0 else 1, this)
        buildUi()
        onBackPressedDispatcher.addCallback(this, backCallback)
        if (!tabs.restore()) tabs.newTab(null)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        tabs.saveActiveNow()
        super.onStop()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) tabs.trimMemory()
    }

    override fun onDestroy() {
        tabs.destroyAll()
        io.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        val data = i?.data
        if (data != null && (data.scheme == "https" || data.scheme == "http")) {
            val url = data.toString().replaceFirst("http://", "https://")
            val a = tabs.active
            if (a != null && a.url == null) tabs.load(a, url) else tabs.newTab(url)
        }
        debugIntent(i)
    }

    // ---- building the window ----

    private val ink = Color.rgb(15, 15, 15)

    private fun borderless(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return tv.resourceId
    }

    private fun iconBtn(icon: Int, description: String, onClick: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = description
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundResource(borderless())
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick(it) }
    }

    private fun buildUi() {
        backBtn = iconBtn(R.drawable.ic_back, "Back") { handleBack() }
        forwardBtn = iconBtn(R.drawable.ic_forward, "Forward") { tabs.active?.web?.takeIf { it.canGoForward() }?.goForward() }
        reloadBtn = iconBtn(R.drawable.ic_refresh, "Reload") { reloadOrStop() }
        val newBtn = iconBtn(R.drawable.ic_add, "New tab") { newTab() }
        val menuBtn = iconBtn(R.drawable.ic_more, "Menu") { showMenu(it) }

        urlBox = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(ink)
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
            // Idle: just the site. Focused: the full address, selected and ready to replace.
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setText(tabs.active?.url?.takeIf { it != "about:blank" } ?: "")
                    post { selectAll() }
                } else {
                    showAddress()
                }
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

        tabCount = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(ink)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(2), ink)
            }
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(24), Gravity.CENTER)
        }
        val tabsBtn = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setBackgroundResource(borderless())
            isClickable = true
            contentDescription = "Tabs"
            setOnClickListener { openSwitcher() }
            addView(tabCount)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(backBtn)
            addView(forwardBtn)
            addView(reloadBtn)
            addView(urlBox)
            addView(newBtn)
            addView(tabsBtn)
            addView(menuBtn)
        }

        // The thin line under the toolbar is always 2dp tall: the divider sits at its bottom and the load bar draws
        // over it. Showing or hiding the bar never changes the page's height, so nothing reflows.
        loadBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
            progressTintList = ColorStateList.valueOf(ink)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
        }
        val divider = View(this).apply {
            setBackgroundColor(Color.rgb(232, 232, 232))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1), Gravity.BOTTOM)
        }
        val strip = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
            addView(divider)
            addView(loadBar)
        }

        findBox = EditText(this).apply {
            setSingleLine(true)
            hint = "Find in page"
            textSize = 15f
            setTextColor(ink)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(12) }
            doAfterTextChanged { text -> tabs.active?.web?.findAllAsync(text?.toString() ?: "") }
            setOnEditorActionListener { _, _, _ ->
                tabs.active?.web?.findNext(true)
                true
            }
        }
        findCount = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(100, 100, 100))
            setPadding(dp(8), 0, dp(8), 0)
        }
        findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            addView(findBox)
            addView(findCount)
            addView(iconBtn(R.drawable.ic_up, "Previous match") { tabs.active?.web?.findNext(false) })
            addView(iconBtn(R.drawable.ic_down, "Next match") { tabs.active?.web?.findNext(true) })
            addView(iconBtn(R.drawable.ic_close, "Close find") { closeFind() })
        }

        webHost = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        startPage = StartPage(this, { url -> navigate(url) }, { focusAddress() })

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(toolbar)
            addView(strip)
            addView(findBar)
            addView(webHost)
        }
        panelLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
            isClickable = true   // swallow touches so nothing reaches the page hidden underneath
        }
        videoLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
        }
        frame = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(root, full)
            addView(panelLayer, FrameLayout.LayoutParams(full))
            addView(videoLayer, FrameLayout.LayoutParams(full))
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
    }

    // ---- TabManager callbacks ----

    override fun createWebView(tab: Tab): WebView = factory.create(tab)

    override fun activeChanged(tab: Tab) {
        webHost.removeAllViews()
        val web = tab.web
        if (web != null) {
            webHost.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.onResume()
            web.requestFocus()
        } else {
            startPage.refresh(bookmarks.items, history.recent(6), Search.name(prefs.searchEngine))
            webHost.addView(startPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (findBar.visibility == View.VISIBLE) closeFind()
        updateToolbar()
    }

    override fun tabsChanged() {
        updateTabCount()
        switcher?.refresh(false)
    }

    // ---- BrowserHost ----

    override fun tabChanged(tab: Tab) {
        if (tab === tabs.active) updateToolbar()
        tabs.markDirty()
    }

    override fun pageFinished(tab: Tab) {
        tab.url?.let { history.add(it, tab.title) }
    }

    override fun createWindow(opener: Tab, resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val child = Tab(tabs.allocateId(), "about:blank", "New tab", opener.id)
        val web = factory.create(child)
        child.web = web
        transport.webView = web
        resultMsg.sendToTarget()
        tabs.adopt(child, opener)
        return true
    }

    override fun showCustom(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        videoLayer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        videoLayer.visibility = View.VISIBLE
        setSystemBars(false)
        updateUnderlayAccessibility()
    }

    override fun hideCustom() {
        val view = customView ?: return
        videoLayer.removeView(view)
        videoLayer.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        setSystemBars(true)
        updateUnderlayAccessibility()
    }

    override fun chooseFile(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try {
            fileLauncher.launch(params.createIntent())
            true
        } catch (_: Exception) {
            fileCallback = null
            callback.onReceiveValue(null)
            false
        }
    }

    override fun sitePermission(request: PermissionRequest) {
        val wanted = ArrayList<String>()
        val needed = ArrayList<String>()
        val what = ArrayList<String>()
        for (r in request.resources) {
            when (r) {
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> wanted.add(r)   // DRM playback: always fine
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                    wanted.add(r)
                    needed.add(Manifest.permission.CAMERA)
                    what.add("camera")
                }
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                    wanted.add(r)
                    needed.add(Manifest.permission.RECORD_AUDIO)
                    what.add("microphone")
                }
            }
        }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        if (needed.isEmpty()) {
            request.grant(wanted.toTypedArray())
            return
        }
        val site = request.origin.host ?: "This site"
        AlertDialog.Builder(this)
            .setTitle("Allow $site?")
            .setMessage("It wants to use your ${what.joinToString(" and ")}.")
            .setPositiveButton("Allow") { _, _ ->
                pendingPermission = request
                pendingResources = wanted.toTypedArray()
                permissionLauncher.launch(needed.toTypedArray())
            }
            .setNegativeButton("Block") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    override fun geolocation(origin: String, callback: GeolocationPermissions.Callback) {
        val site = Uri.parse(origin).host ?: origin
        AlertDialog.Builder(this)
            .setTitle("Share your location?")
            .setMessage("$site wants to know where you are.")
            .setPositiveButton("Allow") { _, _ ->
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton("Block") { _, _ -> callback.invoke(origin, false, false) }
            .setOnCancelListener { callback.invoke(origin, false, false) }
            .show()
    }

    override fun linkMenu(web: WebView, tab: Tab): Boolean {
        val r = web.hitTestResult
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> showLinkDialog(tab, extra, null)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // For a linked image, "extra" is the picture: ask the page for the link it points to.
                val msg = Handler(Looper.getMainLooper()) { m ->
                    val link = m.data?.getString("url")
                    if (link != null) showLinkDialog(tab, link, extra)
                    true
                }.obtainMessage()
                web.requestFocusNodeHref(msg)
            }
            WebView.HitTestResult.IMAGE_TYPE -> showLinkDialog(tab, null, extra)
            else -> return false
        }
        return true
    }

    override fun renderGone(tab: Tab) {
        tabs.renderGone(tab)
    }

    // ---- toolbar and address bar ----

    private fun displayUrl(url: String): String {
        if (!url.startsWith("http")) return ""
        return Uri.parse(url).host?.removePrefix("www.") ?: url
    }

    private fun showAddress() {
        val u = tabs.active?.url
        urlBox.setText(if (u == null) "" else displayUrl(u))
        urlBox.hint = "Search ${Search.name(prefs.searchEngine)} or type a web address"
    }

    private fun updateTabCount() {
        val n = tabs.tabs.size
        tabCount.text = n.toString()
        tabCount.textSize = if (n >= 100) 10f else 12f
    }

    private fun updateToolbar() {
        val t = tabs.active ?: return
        val w = t.web
        val hasOpener = t.openerId != -1L && tabs.tabs.any { it.id == t.openerId }
        backBtn.alpha = if (w?.canGoBack() == true || hasOpener) 1f else 0.35f
        forwardBtn.alpha = if (w?.canGoForward() == true) 1f else 0.35f
        reloadBtn.setImageResource(if (t.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        reloadBtn.contentDescription = if (t.loading) "Stop" else "Reload"
        reloadBtn.alpha = if (w != null) 1f else 0.35f
        if (t.loading) {
            loadBar.visibility = View.VISIBLE
            loadBar.progress = t.progress
        } else {
            loadBar.visibility = View.INVISIBLE
        }
        if (!urlBox.hasFocus()) showAddress()
        updateTabCount()
    }

    private fun go() {
        val url = Search.resolve(urlBox.text.toString(), prefs.searchEngine) ?: return
        urlBox.clearFocus()
        hideKeyboard()
        navigate(url)
    }

    private fun navigate(url: String) {
        tabs.active?.let { tabs.load(it, url) }
    }

    private fun focusAddress() {
        urlBox.requestFocus()
        urlBox.post { WindowCompat.getInsetsController(window, urlBox).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun hideKeyboard() {
        WindowCompat.getInsetsController(window, frame).hide(WindowInsetsCompat.Type.ime())
    }

    private fun reloadOrStop() {
        val t = tabs.active ?: return
        val w = t.web ?: return
        if (t.loading) w.stopLoading() else w.reload()
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

    // ---- tabs ----

    private fun newTab() {
        closePanel()
        tabs.newTab(null)
    }

    private fun closeTab(tab: Tab) {
        tabs.close(tab)
    }

    private fun openSwitcher() {
        hideKeyboard()
        tabs.active?.let { tabs.captureThumb(it) }
        val s = TabSwitcher(
            this, tabs,
            onPick = { t ->
                closePanel()
                tabs.select(t)
            },
            onClose = { t -> closeTab(t) },
            onNew = { newTab() },
            onCloseAll = { confirmCloseAll() },
            onBack = { closePanel() }
        )
        switcher = s
        showPanel(s)
        s.refresh(true)
    }

    private fun confirmCloseAll() {
        AlertDialog.Builder(this)
            .setTitle("Close all ${tabs.tabs.size} tabs?")
            .setPositiveButton("Close all") { _, _ ->
                closePanel()
                tabs.closeAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectRelative(step: Int) {
        val list = tabs.tabs
        val i = list.indexOf(tabs.active)
        if (i < 0 || list.size < 2) return
        tabs.select(list[(i + step + list.size) % list.size])
    }

    // ---- panels ----

    private fun showPanel(view: View) {
        panelLayer.removeAllViews()
        panelLayer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panelLayer.visibility = View.VISIBLE
        panel = view
        updateUnderlayAccessibility()
    }

    private fun closePanel() {
        panelLayer.removeAllViews()
        panelLayer.visibility = View.GONE
        panel = null
        switcher = null
        updateUnderlayAccessibility()
    }

    /** While a panel or fullscreen video covers the page, screen readers should not read the page behind it. */
    private fun updateUnderlayAccessibility() {
        root.importantForAccessibility = if (panel != null || customView != null) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
    }

    private fun handleBack(): Boolean {
        if (customView != null) {
            hideCustom()
            return true
        }
        if (panel != null) {
            closePanel()
            return true
        }
        if (findBar.visibility == View.VISIBLE) {
            closeFind()
            return true
        }
        val t = tabs.active ?: return false
        val w = t.web
        if (w != null && w.canGoBack()) {
            w.goBack()
            return true
        }
        // A tab opened from another tab: back closes it and returns to where you came from.
        if (t.openerId != -1L && tabs.tabs.any { it.id == t.openerId }) {
            tabs.close(t)
            return true
        }
        return false
    }

    private fun confirm(title: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openBookmarks() {
        val p = ListPanel(this) { closePanel() }
        fun rows(): List<ListPanel.Row> = bookmarks.items.map { b ->
            ListPanel.Row(
                title = b.title,
                subtitle = b.url,
                onClick = {
                    closePanel()
                    navigate(b.url)
                },
                onLong = {
                    confirm("Remove this bookmark?", "Remove") {
                        bookmarks.remove(b.url)
                        p.update(rows())
                    }
                }
            )
        }
        p.show("Bookmarks", rows())
        showPanel(p)
    }

    private fun openHistory() {
        val p = ListPanel(this) { closePanel() }
        fun rows(): List<ListPanel.Row> = history.all.take(300).map { e ->
            ListPanel.Row(
                title = e.title,
                subtitle = e.url,
                onClick = {
                    closePanel()
                    navigate(e.url)
                },
                onLong = {
                    history.remove(e.url)
                    p.update(rows())
                }
            )
        }
        p.show("History", rows(), "Clear all") {
            confirm("Clear all history?", "Clear") {
                history.clear()
                p.update(rows())
            }
        }
        showPanel(p)
    }

    private fun openSettings() {
        val p = ListPanel(this) { closePanel() }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        fun rows(): List<ListPanel.Row> = listOf(
            ListPanel.Row("Search engine", Search.name(prefs.searchEngine), onClick = {
                pickEngine {
                    p.update(rows())
                    showAddress()
                }
            }),
            ListPanel.Row(
                "Desktop site", "Show the full desktop version of websites", checked = prefs.desktopSite,
                onClick = {
                    setDesktop(!prefs.desktopSite)
                    p.update(rows())
                }
            ),
            ListPanel.Row("Clear browsing data", "History, cookies and cache", onClick = { clearData() }),
            ListPanel.Row("About Playhead", "Version $version, with the Better YouTube engine", onClick = {})
        )
        p.show("Settings", rows())
        showPanel(p)
    }

    private fun pickEngine(done: () -> Unit) {
        val keys = Search.ENGINES.keys.toList()
        val names = keys.map { Search.name(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Search engine")
            .setSingleChoiceItems(names, keys.indexOf(prefs.searchEngine)) { d, which ->
                prefs.searchEngine = keys[which]
                d.dismiss()
                done()
            }
            .show()
    }

    private fun setDesktop(on: Boolean) {
        prefs.desktopSite = on
        tabs.tabs.forEach { t -> t.web?.let { factory.applySettings(it) } }
        tabs.active?.web?.reload()
    }

    private fun clearData() {
        val labels = arrayOf("Browsing history", "Cookies and site data (signs you out)", "Cached images and files")
        val checked = booleanArrayOf(true, true, true)
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data")
            .setMultiChoiceItems(labels, checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Clear") { _, _ ->
                if (checked[0]) history.clear()
                if (checked[1]) {
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                }
                if (checked[2]) tabs.tabs.firstNotNullOfOrNull { it.web }?.clearCache(true)
                Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- menu, find, sharing ----

    private fun showMenu(anchor: View) {
        val t = tabs.active ?: return
        val url = t.url
        val web = t.web
        val isPage = url != null && url.startsWith("http")
        val pm = PopupMenu(this, anchor, Gravity.END)
        val m = pm.menu
        m.add(0, 1, 0, "New tab")
        m.add(0, 2, 1, if (url != null && bookmarks.has(url)) "Remove bookmark" else "Add bookmark").isEnabled = isPage
        m.add(0, 3, 2, "Bookmarks")
        m.add(0, 4, 3, "History")
        m.add(0, 5, 4, "Downloads")
        m.add(0, 6, 5, "Find in page").isEnabled = web != null
        m.add(0, 7, 6, "Share").isEnabled = isPage
        m.add(0, 8, 7, "Desktop site").apply {
            isCheckable = true
            isChecked = prefs.desktopSite
        }
        m.add(0, 9, 8, "Settings")
        m.add(0, 10, 9, "Close all tabs")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> newTab()
                2 -> toggleBookmark()
                3 -> openBookmarks()
                4 -> openHistory()
                5 -> openDownloads()
                6 -> openFind()
                7 -> shareCurrent()
                8 -> setDesktop(!prefs.desktopSite)
                9 -> openSettings()
                10 -> confirmCloseAll()
            }
            true
        }
        pm.show()
    }

    private fun toggleBookmark() {
        val t = tabs.active ?: return
        val url = t.url ?: return
        if (!url.startsWith("http")) return
        val added = bookmarks.toggle(url, t.title)
        Toast.makeText(this, if (added) "Bookmarked" else "Bookmark removed", Toast.LENGTH_SHORT).show()
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can show downloads", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFind() {
        val web = tabs.active?.web ?: return
        findBar.visibility = View.VISIBLE
        web.setFindListener { active, matches, _ ->
            findCount.text = when {
                findBox.text.isNullOrEmpty() -> ""
                matches == 0 -> "0"
                else -> "${active + 1}/$matches"
            }
        }
        findBox.requestFocus()
        findBox.post { WindowCompat.getInsetsController(window, findBox).show(WindowInsetsCompat.Type.ime()) }
    }

    private fun closeFind() {
        if (findBar.visibility != View.VISIBLE) return
        tabs.active?.web?.clearMatches()
        findBar.visibility = View.GONE
        findBox.setText("")
        findCount.text = ""
        hideKeyboard()
    }

    private fun shareCurrent() {
        val url = tabs.active?.url ?: return
        share(url)
    }

    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share"))
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun showLinkDialog(tab: Tab, link: String?, image: String?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (link != null) {
            labels.add("Open in new tab")
            actions.add { tabs.newTab(link, tab, true) }
            labels.add("Open in background tab")
            actions.add { tabs.newTab(link, tab, false) }
            labels.add("Copy link")
            actions.add { copy(link) }
            labels.add("Share link")
            actions.add { share(link) }
        }
        if (image != null) {
            labels.add("Open image in new tab")
            actions.add { tabs.newTab(image, tab, true) }
            labels.add("Save image")
            actions.add { factory.download(image, tab.web?.settings?.userAgentString, null, null) }
            labels.add("Copy image address")
            actions.add { copy(image) }
        }
        AlertDialog.Builder(this)
            .setTitle((link ?: image)?.take(80))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    // ---- keyboard shortcuts (for tablets with a keyboard) ----

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_T -> newTab()
                KeyEvent.KEYCODE_W -> tabs.active?.let { closeTab(it) }
                KeyEvent.KEYCODE_L -> focusAddress()
                KeyEvent.KEYCODE_R -> reloadOrStop()
                KeyEvent.KEYCODE_F -> openFind()
                KeyEvent.KEYCODE_D -> toggleBookmark()
                KeyEvent.KEYCODE_H -> openHistory()
                KeyEvent.KEYCODE_TAB -> selectRelative(if (e.isShiftPressed) -1 else 1)
                else -> return super.dispatchKeyEvent(e)
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.isAltPressed) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> handleBack()
                KeyEvent.KEYCODE_DPAD_RIGHT -> tabs.active?.web?.takeIf { it.canGoForward() }?.goForward()
                else -> return super.dispatchKeyEvent(e)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---- debug-only tools for stress testing (ignored in release builds) ----

    private fun debugIntent(i: Intent?) {
        if (!debuggable || i == null) return
        val open = i.getIntExtra("open_tabs", 0)
        if (open > 0) {
            repeat(open) { k ->
                val url = DEBUG_URLS[k % DEBUG_URLS.size] + "#tab$k"
                tabs.addInactive(url, "Test tab $k")
            }
        }
        val cycle = i.getIntExtra("cycle_tabs", 0)
        if (cycle > 0) {
            val gap = i.getLongExtra("cycle_ms", 2500L)
            var k = 0
            val step = object : Runnable {
                override fun run() {
                    if (k < cycle && k < tabs.tabs.size) {
                        tabs.select(tabs.tabs[k])
                        k++
                        ui.postDelayed(this, gap)
                    }
                }
            }
            ui.post(step)
        }
    }

    private companion object {
        val DEBUG_URLS = listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/results?search_query=music",
            "https://en.wikipedia.org/wiki/Special:Random",
            "https://www.youtube.com/watch?v=kJQP7kiw5Fk",
            "https://news.ycombinator.com/",
            "https://www.youtube.com/watch?v=LXb3EKWsInQ",
            "https://www.youtube.com/",
            "https://www.wikipedia.org/"
        )
    }
}
