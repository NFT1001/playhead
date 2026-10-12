package app.playhead.browser

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

private const val TAG = "PLH-UPD"

/** The Better YouTube script: the bundled copy, or a newer downloaded one that was checked before it was saved. */
object ScriptStore {
    data class Loaded(val source: String, val version: String, val downloaded: Boolean)

    fun versionOf(src: String): String =
        Regex("@version\\s+(\\S+)").find(src.take(4000))?.groupValues?.get(1) ?: "0"

    /** True when version [a] is newer than [b] (dotted numbers, e.g. 5.5.1 > 5.5). */
    fun newer(a: String, b: String): Boolean {
        fun parts(s: String) = s.split('.').map { p -> p.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    fun valid(src: String) = src.contains("==UserScript==") && src.contains("__betterYTLoaded")

    private fun dir(c: Context) = File(c.filesDir, "script")

    fun appCode(c: Context): Int = try {
        PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0)).toInt()
    } catch (_: Exception) {
        0
    }

    fun load(c: Context): Loaded? {
        val bundled = try {
            c.assets.open("betteryt.js").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
        val bundledVersion = bundled?.let { versionOf(it) } ?: "0"
        try {
            val f = File(dir(c), "betteryt.js")
            val m = File(dir(c), "meta.json")
            // A downloaded script is only used with the app version it was downloaded for, and only while it is
            // newer than the bundled one. After an app update the bundled script wins again unless it is older.
            if (f.isFile && m.isFile && JSONObject(m.readText()).getInt("appCode") == appCode(c)) {
                val src = f.readText()
                val v = versionOf(src)
                if (valid(src) && newer(v, bundledVersion)) return Loaded(src, v, true)
            }
        } catch (_: Exception) {
            // A damaged download is ignored; the bundled script is used.
        }
        return bundled?.let { Loaded(it, bundledVersion, false) }
    }

    fun save(c: Context, src: String) {
        val d = dir(c)
        d.mkdirs()
        writeAtomic(File(d, "betteryt.js"), src.toByteArray())
        writeAtomic(File(d, "meta.json"), JSONObject().put("appCode", appCode(c)).toString().toByteArray())
    }
}

/**
 * Looks for updates at [Prefs.updateBase] (default: the latest GitHub Release): manifest.json lists the script and the
 * app with a SHA-256 for each. The script is applied by itself; the app is only installed when the person asks.
 */
class UpdateManager(private val context: Context, private val prefs: Prefs) {
    data class AppUpdate(val code: Int, val name: String, val file: String, val sha256: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    var available: AppUpdate? = null
        private set
    var status: String = ""
        private set

    private fun base(): String = prefs.updateBase ?: BuildConfig.UPDATE_BASE

    fun checkIfDue() {
        if (prefs.autoUpdate && System.currentTimeMillis() - prefs.lastUpdateCheck > 12 * 3600_000L) check(null)
    }

    fun check(done: ((String) -> Unit)?) {
        io.execute {
            val msg = try {
                runCheck()
            } catch (e: Exception) {
                Log.w(TAG, "check failed: $e")
                "Couldn't check for updates"
            }
            main.post {
                status = msg
                done?.invoke(msg)
            }
        }
    }

    private fun runCheck(): String {
        val mf = JSONObject(String(fetch(base() + "manifest.json", 65_536)))
        var note = ""
        val s = mf.optJSONObject("script")
        if (s != null) {
            val current = ScriptStore.load(context)?.version ?: "0"
            val v = s.getString("version")
            if (ScriptStore.newer(v, current)) {
                val bytes = fetch(base() + safeName(s.getString("file")), 2_000_000)
                require(sha256(bytes).equals(s.getString("sha256"), ignoreCase = true)) { "script hash mismatch" }
                val src = String(bytes)
                require(ScriptStore.valid(src)) { "not the Better YouTube script" }
                require(ScriptStore.versionOf(src) == v) { "script version does not match the manifest" }
                ScriptStore.save(context, src)
                ScriptHolder.invalidate()
                note = "Script updated to $v. "
            }
        }
        val a = mf.optJSONObject("app")
        val code = ScriptStore.appCode(context)
        available = if (a != null && a.getInt("versionCode") > code) {
            AppUpdate(a.getInt("versionCode"), a.getString("versionName"), safeName(a.getString("file")), a.getString("sha256"))
        } else {
            null
        }
        prefs.lastUpdateCheck = System.currentTimeMillis()
        val u = available
        return when {
            u != null -> "${note}Version ${u.name} is available. Tap to install"
            note.isNotEmpty() -> note + "New tabs use it"
            else -> "Up to date (script ${ScriptStore.load(context)?.version ?: "?"})"
        }
    }

    /** Download the new APK, check it, and hand it to the system installer (which asks the person to confirm). */
    fun install(activity: Activity, progress: (String) -> Unit) {
        val u = available ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow Playhead to install apps, then tap Install again", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        io.execute {
            val msg = try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "playhead.apk")
                downloadTo(base() + u.file, file, 150_000_000L, u.sha256) { pct -> main.post { progress("Downloading $pct%") } }
                val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
                require(info != null && info.packageName == context.packageName) { "not a Playhead package" }
                require(PackageInfoCompat.getLongVersionCode(info).toInt() == u.code) { "wrong version in the package" }
                commit(file)
                "Confirm the install"
            } catch (e: Exception) {
                Log.w(TAG, "install failed: $e")
                "Update failed: ${e.message}"
            }
            main.post { progress(msg) }
        }
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("playhead.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    // ---- network helpers ----

    private fun safeName(name: String): String {
        require(Regex("[A-Za-z0-9._-]{1,80}").matches(name)) { "bad file name in the manifest" }
        return name
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Playhead/${BuildConfig.VERSION_NAME}")
        if (c.responseCode != 200) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c
    }

    private fun fetch(url: String, max: Int): ByteArray {
        val c = open(url)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > max) throw IOException("file too large")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun downloadTo(url: String, file: File, max: Long, sha: String, pct: (Int) -> Unit) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            file.outputStream().use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(65_536)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done > max) throw IOException("file too large")
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last && p % 10 == 0) { last = p; pct(p) }
                        }
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha, ignoreCase = true)) {
                file.delete()
                throw IOException("download does not match its checksum")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Receives the installer's answer: shows the system confirmation, or tells the person why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown reason"
                Log.w(TAG, "install status: $why")
                Toast.makeText(context, "Update not installed: $why", Toast.LENGTH_LONG).show()
            }
        }
    }
}
