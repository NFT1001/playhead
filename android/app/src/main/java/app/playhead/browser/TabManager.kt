package app.playhead.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Log
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

/**
 * One tab. Cheap by design: a tab that is not on screen is only this small record plus a state file and a
 * thumbnail on disk. [web] is null until the tab is actually shown.
 */
class Tab(val id: Long, var url: String?, var title: String, var openerId: Long = -1L) {
    var web: WebView? = null
    var loading = false
    var progress = 0
    var lastActive = System.currentTimeMillis()
}

/**
 * Owns all tabs. Memory plan for 100+ tabs: at most the active tab plus [hotLimit] recently used tabs keep a live
 * WebView. Every other tab is saved to disk (page state and a small thumbnail) and its WebView is destroyed.
 */
class TabManager(
    context: Context,
    private val io: Executor,
    private val hotLimit: Int,
    private val cb: Callbacks
) {
    interface Callbacks {
        fun createWebView(tab: Tab): WebView
        fun activeChanged(tab: Tab)
        fun tabsChanged()
    }

    val tabs = ArrayList<Tab>()
    var active: Tab? = null
        private set

    private var nextId = 1L
    private val handler = Handler(Looper.getMainLooper())
    private val dir = File(context.filesDir, "tabs").apply { mkdirs() }
    private val listFile = File(dir, "tabs.json")
    private val thumbs = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
    }
    private val persist = Runnable { persistNow() }

    // Which tabs have a thumbnail file. Lets the switcher skip the disk lookup for tabs that never had one.
    private val thumbOnDisk = HashSet<Long>()

    init {
        dir.list()?.forEach { n ->
            if (n.endsWith(".jpg")) n.removeSuffix(".jpg").toLongOrNull()?.let { thumbOnDisk.add(it) }
        }
    }
    private fun stateFile(id: Long) = File(dir, "$id.state")
    private fun thumbFile(id: Long) = File(dir, "$id.jpg")

    fun liveCount() = tabs.count { it.web != null }

    fun allocateId(): Long = nextId++

    // ---- creating and finding tabs ----

    fun newTab(url: String?, opener: Tab? = null, activate: Boolean = true): Tab {
        val t = Tab(allocateId(), url, url ?: "New tab", opener?.id ?: -1L)
        insert(t, opener)
        if (activate) select(t) else markDirty()
        return t
    }

    /** Adds a tab that is not loaded and not shown (used when restoring and by the debug tools). */
    fun addInactive(url: String, title: String): Tab {
        val t = Tab(allocateId(), url, title)
        tabs.add(t)
        cb.tabsChanged()
        markDirty()
        return t
    }

    /** A page opened a window (target=_blank, window.open): the tab already has its WebView. */
    fun adopt(tab: Tab, opener: Tab?) {
        insert(tab, opener)
        select(tab)
    }

    private fun insert(t: Tab, opener: Tab?) {
        val at = if (opener != null) tabs.indexOf(opener) else -1
        if (at >= 0) tabs.add(at + 1, t) else tabs.add(t)
        cb.tabsChanged()
    }

    // ---- switching ----

    fun select(tab: Tab) {
        val old = active
        if (old != null && old !== tab) background(old)
        active = tab
        tab.lastActive = System.currentTimeMillis()
        if (tab.url != null && tab.web == null) {
            tab.web = cb.createWebView(tab)
            restoreState(tab)
        }
        enforceHotLimit()
        cb.activeChanged(tab)
        markDirty()
        Log.d(TAG, "tabs=${tabs.size} live=${liveCount()}")
    }

    /** Opens [url] in [tab]; a start-page tab gets its first WebView here. */
    fun load(tab: Tab, url: String) {
        tab.url = url
        tab.title = url
        if (tab.web == null) {
            tab.web = cb.createWebView(tab)
            if (tab === active) cb.activeChanged(tab)
        }
        tab.web?.loadUrl(url)
        markDirty()
    }

    private fun background(tab: Tab) {
        val w = tab.web ?: return
        captureThumb(tab)
        saveState(tab)
        w.evaluateJavascript(PAUSE_MEDIA, null)
        w.onPause()
    }

    private fun enforceHotLimit() {
        val hot = tabs.filter { it.web != null && it !== active }.sortedByDescending { it.lastActive }
        for (i in hotLimit until hot.size) discard(hot[i])
    }

    private fun discard(tab: Tab) {
        val w = tab.web ?: return
        if (tab === active) return
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
        tab.web = null
    }

    /** Low memory: drop every WebView that is not on screen. They come back from disk when opened. */
    fun trimMemory() {
        tabs.filter { it !== active }.forEach { discard(it) }
        thumbs.evictAll()
    }

    // ---- closing ----

    fun close(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasActive = tab === active
        tab.web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        tab.web = null
        tabs.removeAt(idx)
        thumbs.remove(tab.id)
        thumbOnDisk.remove(tab.id)
        io.execute {
            stateFile(tab.id).delete()
            thumbFile(tab.id).delete()
        }
        if (wasActive) {
            active = null
            val next = tabs.firstOrNull { it.id == tab.openerId } ?: tabs.getOrNull(minOf(idx, tabs.size - 1))
            if (next != null) select(next) else newTab(null)
        }
        cb.tabsChanged()
        markDirty()
    }

    fun closeAll() {
        val all = ArrayList(tabs)
        active = null
        all.forEach { t ->
            t.web?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            t.web = null
            io.execute {
                stateFile(t.id).delete()
                thumbFile(t.id).delete()
            }
        }
        tabs.clear()
        thumbs.evictAll()
        thumbOnDisk.clear()
        newTab(null)
    }

    /** The WebView's process died: rebuild it and reload the page. */
    fun renderGone(tab: Tab) {
        tab.web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        tab.web = null
        if (tab === active && tab.url != null) {
            tab.web = cb.createWebView(tab)
            tab.web?.loadUrl(tab.url!!)
            cb.activeChanged(tab)
        }
    }

    fun destroyAll() {
        tabs.forEach { t ->
            t.web?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            t.web = null
        }
    }

    // ---- saving and restoring ----

    fun saveState(tab: Tab) {
        val w = tab.web ?: return
        val b = Bundle()
        if (w.saveState(b) == null) return
        val p = Parcel.obtain()
        try {
            p.writeBundle(b)
            val bytes = p.marshall()
            io.execute { writeAtomic(stateFile(tab.id), bytes) }
        } finally {
            p.recycle()
        }
    }

    private fun restoreState(tab: Tab) {
        val w = tab.web ?: return
        val f = stateFile(tab.id)
        if (f.exists()) {
            try {
                val bytes = f.readBytes()
                val p = Parcel.obtain()
                try {
                    p.unmarshall(bytes, 0, bytes.size)
                    p.setDataPosition(0)
                    val b = p.readBundle(TabManager::class.java.classLoader)
                    if (b != null && w.restoreState(b) != null) return
                } finally {
                    p.recycle()
                }
            } catch (_: Exception) {
                // Fall through and just load the address.
            }
        }
        tab.url?.let { w.loadUrl(it) }
    }

    /** Called when the app leaves the screen: make sure what you were looking at survives being killed. */
    fun saveActiveNow() {
        active?.let {
            captureThumb(it)
            saveState(it)
        }
        handler.removeCallbacks(persist)
        persistNow()
    }

    fun markDirty() {
        handler.removeCallbacks(persist)
        handler.postDelayed(persist, 800)
    }

    private fun persistNow() {
        val arr = JSONArray()
        for (t in tabs) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("url", t.url ?: JSONObject.NULL)
                    .put("title", t.title)
                    .put("opener", t.openerId)
                    .put("t", t.lastActive)
            )
        }
        val text = JSONObject().put("active", active?.id ?: -1L).put("next", nextId).put("tabs", arr).toString()
        io.execute { writeAtomic(listFile, text.toByteArray()) }
    }

    /** Loads the saved tab list. Only the active tab gets a WebView. Returns false if there was nothing to restore. */
    fun restore(): Boolean {
        try {
            if (!listFile.exists()) return false
            val root = JSONObject(listFile.readText())
            val arr = root.getJSONArray("tabs")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val url = if (o.isNull("url")) null else o.getString("url")
                val t = Tab(o.getLong("id"), url, o.optString("title", url ?: "New tab"), o.optLong("opener", -1L))
                t.lastActive = o.optLong("t", 0L)
                tabs.add(t)
            }
            nextId = maxOf(root.optLong("next", 1L), (tabs.maxOfOrNull { it.id } ?: 0L) + 1)
            if (tabs.isEmpty()) return false
            val activeId = root.optLong("active", -1L)
            cb.tabsChanged()
            select(tabs.firstOrNull { it.id == activeId } ?: tabs.last())
            return true
        } catch (_: Exception) {
            tabs.clear()
            return false
        }
    }

    // ---- thumbnails ----

    fun captureThumb(tab: Tab) {
        val w = tab.web ?: return
        val ww = w.width
        val wh = w.height
        if (ww <= 0 || wh <= 0) return
        val tw = 360
        val th = (tw.toFloat() * wh / ww).toInt().coerceAtLeast(1)
        val bmp = try {
            Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565)
        } catch (_: OutOfMemoryError) {
            return
        }
        try {
            val c = Canvas(bmp)
            val s = tw.toFloat() / ww
            c.scale(s, s)
            w.draw(c)
        } catch (_: Exception) {
            return
        }
        thumbs.put(tab.id, bmp)
        thumbOnDisk.add(tab.id)
        io.execute {
            try {
                FileOutputStream(thumbFile(tab.id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            } catch (_: Exception) {
                // No thumbnail is not a problem.
            }
        }
    }

    /** Gives the thumbnail for [id] to [done] on the main thread, reading it from disk only if needed. */
    fun loadThumb(id: Long, done: (Bitmap?) -> Unit) {
        thumbs.get(id)?.let {
            done(it)
            return
        }
        if (!thumbOnDisk.contains(id)) {
            done(null)
            return
        }
        io.execute {
            val f = thumbFile(id)
            val b = if (f.exists()) {
                try {
                    BitmapFactory.decodeFile(f.path)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            if (b != null) thumbs.put(id, b)
            handler.post { done(b) }
        }
    }

    private companion object {
        const val TAG = "PLH"
        const val PAUSE_MEDIA =
            "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})"
    }
}
