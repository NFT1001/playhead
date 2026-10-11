package app.playhead.browser

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import org.json.JSONObject

/**
 * What this device can really do, asked of Android instead of guessed in the page.
 * The result is handed to the script (window.__BYT_NATIVE) before it runs.
 */
object DeviceInfo {

    private const val TAG = "BYB"
    private const val SCHEMA = 1

    // Frame heights worth testing, highest first, and the tier name the script uses for each.
    private val HEIGHTS = intArrayOf(4320, 2160, 1440, 1080, 720, 480, 360)
    private val TIER = mapOf(
        4320 to "highres", 2160 to "hd2160", 1440 to "hd1440", 1080 to "hd1080",
        720 to "hd720", 480 to "large", 360 to "medium"
    )

    private val MIMES = linkedMapOf(
        "vp9" to "video/x-vnd.on2.vp9",
        "av1" to "video/av01",
        "avc" to "video/avc",
        "hevc" to "video/hevc"
    )

    fun collect(context: Context): JSONObject {
        val started = System.nanoTime()
        val codecs = cachedCodecs(context)

        val screen = screen(context)
        val longEdge = maxOf(screen.first, screen.second)
        val shortEdge = minOf(screen.first, screen.second)

        // Tallest picture a hardware decoder YouTube can use handles at 30 fps.
        // YouTube serves H.264 only up to 1080p, so that is the most it counts for.
        val vp9 = codecs.getJSONObject("vp9").getInt("maxH")
        val av1 = codecs.getJSONObject("av1").getInt("maxH")
        val avc = minOf(codecs.getJSONObject("avc").getInt("maxH"), 1080)
        val decoderHeight = maxOf(vp9, av1, avc).let { if (it == 0) 720 else it }

        // A video fills the long edge at 16:9, so more lines than this cannot be seen.
        val screenHeight = longEdge * 9 / 16
        val screenCap = HEIGHTS.firstOrNull { it <= screenHeight * 1.05 } ?: 360

        val ceiling = HEIGHTS.firstOrNull { it <= minOf(decoderHeight, screenCap) } ?: 360
        val limitedBy = if (decoderHeight < screenCap) "decoder" else "screen"

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }

        val out = JSONObject()
            .put("v", 1)
            .put("api", Build.VERSION.SDK_INT)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("memMB", memory.totalMem / (1024 * 1024))
            .put("lowRam", activityManager.isLowRamDevice)
            .put(
                "screen", JSONObject()
                    .put("long", longEdge)
                    .put("short", shortEdge)
                    .put("hz", refreshRate(context).toDouble())
                    .put("dpr", context.resources.displayMetrics.density.toDouble())
            )
            .put("codecs", codecs)
            .put("ceilingTier", TIER.getValue(ceiling))
            .put("ceilingHeight", ceiling)
            .put("limitedBy", limitedBy)

        Log.i(TAG, "device info ready in ${(System.nanoTime() - started) / 1_000_000} ms: max=${TIER[ceiling]} ($limitedBy)")
        return out
    }

    // ---- codecs (the slow part, so cached per Android build) ----

    private fun cachedCodecs(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("device_info", Context.MODE_PRIVATE)
        val key = "codecs:$SCHEMA:${Build.FINGERPRINT}"
        prefs.getString(key, null)?.let {
            try {
                Log.i(TAG, "codec info from cache")
                return JSONObject(it)
            } catch (_: Exception) { /* rebuild below */ }
        }
        val fresh = JSONObject()
        val started = System.nanoTime()
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        for ((name, mime) in MIMES) fresh.put(name, probe(infos, mime))
        Log.i(TAG, "codec probe took ${(System.nanoTime() - started) / 1_000_000} ms")
        prefs.edit().clear().putString(key, fresh.toString()).apply()
        return fresh
    }

    private fun probe(infos: List<MediaCodecInfo>, mime: String): JSONObject {
        var hw = false
        var sw = false
        var maxH = 0
        var maxH60 = 0
        for (info in infos) {
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val video = try {
                info.getCapabilitiesForType(mime).videoCapabilities
            } catch (_: Exception) {
                null
            } ?: continue
            if (!isHardware(info)) {
                sw = true
                continue
            }
            hw = true
            for (h in HEIGHTS) {
                val w = ((h * 16 + 8) / 9 + 1) / 2 * 2
                try {
                    if (video.areSizeAndRateSupported(w, h, 30.0) && h > maxH) maxH = h
                    if (video.areSizeAndRateSupported(w, h, 60.0) && h > maxH60) maxH60 = h
                } catch (_: Exception) { /* a codec that cannot answer counts as not supporting it */ }
            }
        }
        return JSONObject().put("hw", hw).put("sw", sw).put("maxH", maxH).put("maxH60", maxH60)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated && !info.isSoftwareOnly
        val n = info.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google."))
    }

    // ---- screen ----

    @Suppress("DEPRECATION")
    private fun screen(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun refreshRate(context: Context): Float {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
    }
}
