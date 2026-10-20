package com.example.tiktokxsleppify

import java.net.URI

/**
 * Pure-Kotlin URL rules (no android.* imports) so they can be unit tested on the JVM.
 */
object UrlPolicy {

    /** Hosts the main frame may navigate to: TikTok itself, its CDNs and the login providers it offers. */
    private val allowedHostSuffixes = listOf(
        "tiktok.com", "tiktokv.com", "tiktokv.us", "tiktokcdn.com", "tiktokcdn-us.com",
        "tiktokw.us", "tiktokw.eu", "ttwstatic.com", "byteoversea.com", "ibytedtos.com", "musical.ly",
        // Login flows started from TikTok
        "facebook.com", "apple.com", "twitter.com", "x.com"
    )

    /** Telemetry / ad hosts dropped when "block trackers" is on (substring match, as before). */
    val trackerHosts = listOf(
        "google-analytics.com", "googletagmanager", "doubleclick",
        "googlesyndication", "scorecardresearch.com", "facebook.net",
        "analytics.tiktok.com", "mon.tiktok.com", "mcs.tiktok.com", "stat.tiktok.com", "log.tiktok.com",
        "mon.tiktokv.com", "mon-va.tiktokv.com", "mcs.tiktokv.com", "mcs-va.tiktokv.com",
        "log.tiktokv.com", "log-va.tiktokv.com"
    )

    fun isTracker(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        return trackerHosts.any { host.contains(it) }
    }

    /** Google OAuth pages refuse to load inside WebViews, so we intercept them. */
    fun isGoogleAuth(url: String?): Boolean {
        if (url == null) return false
        return url.contains("accounts.google.com") ||
            url.contains("accounts.youtube.com") ||
            url.contains("google.com/signin") ||
            url.contains("google.com/o/oauth2")
    }

    /** True for http(s) URLs whose host is TikTok / a known CDN / an allowed login provider. */
    fun isAllowedNavigation(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase() ?: return false
            if (scheme != "https" && scheme != "http") return false
            val host = uri.host?.lowercase() ?: return false
            allowedHostSuffixes.any { host == it || host.endsWith(".$it") }
        } catch (e: Exception) {
            false
        }
    }
}
