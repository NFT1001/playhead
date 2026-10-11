package app.playhead.browser

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refuses ad and tracking requests before they leave the device.
 * The rules mirror the script's own AD_REQ pattern, which has been tested live on YouTube.
 * The script tells us when the user turns "Block ad requests" off in the panel.
 */
object Blocker {

    @Volatile
    var enabled = true

    val blocked = AtomicInteger(0)

    // Ad networks: blocked on any site.
    private val BLOCKED_HOSTS = arrayOf("doubleclick.net", "googlesyndication.com", "googleadservices.com")

    // Ad endpoints that live on Google and YouTube's own hosts: only matched there, so an unrelated site
    // that happens to use a path like /pagead/ is left alone.
    private val PATH_HOSTS = arrayOf("youtube.com", "google.com")
    private val BLOCKED_PATHS = arrayOf("/pagead/", "/api/stats/ads", "/pcs/activeview", "/ptracking", "/get_midroll_")

    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        if (matches(host, BLOCKED_HOSTS)) return true
        if (!matches(host, PATH_HOSTS)) return false
        val path = uri.path?.lowercase() ?: return false
        for (p in BLOCKED_PATHS) if (path.contains(p)) return true
        return false
    }

    private fun matches(host: String, domains: Array<String>): Boolean {
        for (d in domains) if (host == d || host.endsWith(".$d")) return true
        return false
    }

    fun emptyResponse() = WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )
}
