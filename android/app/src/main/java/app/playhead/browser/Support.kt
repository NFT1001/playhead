package app.playhead.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/** Writes a file so that a crash half way through never leaves a broken one behind. */
fun writeAtomic(file: File, bytes: ByteArray) {
    try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    } catch (_: Exception) {
        // A failed save must never crash the browser.
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    private val tablet = context.resources.configuration.smallestScreenWidthDp >= 600

    init {
        Search.setCustom(sp.getString("customName", null), sp.getString("customUrl", null))
    }

    val customName: String? get() = sp.getString("customName", null)
    val customUrl: String? get() = sp.getString("customUrl", null)

    fun saveCustomEngine(name: String, url: String) {
        sp.edit().putString("customName", name).putString("customUrl", url).apply()
        Search.setCustom(name, url)
    }

    var searchEngine: String
        get() = sp.getString("search", "youtube") ?: "youtube"
        set(v) { sp.edit().putString("search", v).apply() }

    var autoUpdate: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) { sp.edit().putBoolean("autoUpdate", v).apply() }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }

    /** Overrides where updates come from. Only set by debug builds, for testing against a local server. */
    var updateBase: String?
        get() = sp.getString("updateBase", null)
        set(v) { sp.edit().putString("updateBase", v).apply() }

    /** Tablets get the desktop site by default; phones get the mobile one. */
    var desktopSite: Boolean
        get() = sp.getBoolean("desktop", tablet)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }
}

object Search {
    val ENGINES = linkedMapOf(
        "youtube" to ("YouTube" to "https://www.youtube.com/results?search_query="),
        "brave" to ("Brave Search" to "https://search.brave.com/search?q="),
        "ddg" to ("DuckDuckGo" to "https://duckduckgo.com/?q="),
        "google" to ("Google" to "https://www.google.com/search?q="),
        "bing" to ("Bing" to "https://www.bing.com/search?q="),
        "startpage" to ("Startpage" to "https://www.startpage.com/do/search?q="),
        "ecosia" to ("Ecosia" to "https://www.ecosia.org/search?q="),
        "qwant" to ("Qwant" to "https://www.qwant.com/?q="),
        "yahoo" to ("Yahoo" to "https://search.yahoo.com/search?p="),
        "wikipedia" to ("Wikipedia" to "https://en.wikipedia.org/w/index.php?search=")
    )

    fun name(key: String): String {
        if (key == "custom" && customTemplate != null) return customName ?: "Custom"
        return (ENGINES[key] ?: ENGINES.getValue("youtube")).first
    }

    private var customName: String? = null
    private var customTemplate: String? = null

    fun setCustom(name: String?, template: String?) {
        customName = name
        customTemplate = template
    }

    fun hasCustom() = customTemplate != null

    /** A custom engine is a web address with %s where the search words go. Returns what is wrong, or null if it is fine. */
    fun checkTemplate(t: String): String? = when {
        !t.startsWith("https://") -> "The address must start with https://"
        !t.contains("%s") -> "The address must contain %s where the search words go"
        Uri.parse(t.replace("%s", "x")).host.isNullOrEmpty() -> "That doesn't look like a web address"
        else -> null
    }

    /** What the user typed becomes an address: a web address as is (always https), anything else a search. */
    fun resolve(input: String, engine: String): String? {
        val t = input.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("https://")) return t
        if (t.startsWith("http://")) return "https://" + t.removePrefix("http://")
        val looksLikeAddress = !t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))
        if (looksLikeAddress) return "https://$t"
        val template = if (engine == "custom") customTemplate else null
        if (template != null) return template.replace("%s", Uri.encode(t))
        return (ENGINES[engine] ?: ENGINES.getValue("youtube")).second + Uri.encode(t)
    }
}

/** Runs [build] a moment after the last change and writes the result off the main thread. */
private class Saver(private val io: Executor, private val file: File, private val build: () -> String) {
    private val handler = Handler(Looper.getMainLooper())
    private val run = Runnable {
        val text = build()
        io.execute { writeAtomic(file, text.toByteArray()) }
    }

    fun schedule() {
        handler.removeCallbacks(run)
        handler.postDelayed(run, 1500)
    }
}

class Bookmarks(dir: File, io: Executor) {
    data class Item(val url: String, val title: String)

    val items = ArrayList<Item>()
    private val file = File(dir, "bookmarks.json")
    private val saver = Saver(io, file) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title)) }
        a.toString()
    }

    init {
        try {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    items.add(Item(o.getString("u"), o.optString("t")))
                }
            }
        } catch (_: Exception) {
            items.clear()
        }
    }

    fun has(url: String) = items.any { it.url == url }

    /** Adds the page, or removes it if it is already there. Returns true when it is now bookmarked. */
    fun toggle(url: String, title: String): Boolean {
        val i = items.indexOfFirst { it.url == url }
        val added = i < 0
        if (added) items.add(0, Item(url, title.ifBlank { url })) else items.removeAt(i)
        saver.schedule()
        return added
    }

    fun remove(url: String) {
        items.removeAll { it.url == url }
        saver.schedule()
    }
}

class History(dir: File, io: Executor) {
    class Entry(val url: String, var title: String, var time: Long)

    private val file = File(dir, "history.json")
    private val saver = Saver(io, file) {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("a", it.time)) }
        a.toString()
    }

    // Loaded the first time it is needed, not at startup.
    private val entries: ArrayList<Entry> by lazy {
        val list = ArrayList<Entry>()
        try {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    list.add(Entry(o.getString("u"), o.optString("t"), o.optLong("a")))
                }
            }
        } catch (_: Exception) {
            list.clear()
        }
        list
    }

    val all: List<Entry> get() = entries

    fun recent(n: Int): List<Entry> = entries.take(n)

    fun add(url: String, title: String) {
        if (!url.startsWith("http")) return
        val now = System.currentTimeMillis()
        val i = entries.indexOfFirst { it.url == url }
        if (i >= 0) {
            val e = entries.removeAt(i)
            e.time = now
            if (title.isNotBlank()) e.title = title
            entries.add(0, e)
        } else {
            entries.add(0, Entry(url, title.ifBlank { url }, now))
        }
        while (entries.size > MAX) entries.removeAt(entries.size - 1)
        saver.schedule()
    }

    fun remove(url: String) {
        entries.removeAll { it.url == url }
        saver.schedule()
    }

    fun clear() {
        entries.clear()
        saver.schedule()
    }

    private companion object {
        const val MAX = 1000
    }
}
