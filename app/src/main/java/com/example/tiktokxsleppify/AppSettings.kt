package com.example.tiktokxsleppify

import android.content.Context

/** Pages the app can open on start (also used by the menu tabs). */
enum class StartPage(val key: String, val url: String) {
    FOR_YOU("foryou", "https://www.tiktok.com/foryou"),
    FOLLOWING("following", "https://www.tiktok.com/following"),
    EXPLORE("explore", "https://www.tiktok.com/explore");

    fun next(): StartPage = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromKey(key: String?): StartPage = values().firstOrNull { it.key == key } ?: FOR_YOU
    }
}

/** Thin wrapper around SharedPreferences so every setting has one name and one default. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("tv_tiktok_settings", Context.MODE_PRIVATE)

    /** Pure-black surfaces for OLED/AMOLED displays. */
    var amoledMode: Boolean
        get() = prefs.getBoolean("amoled_mode", true)
        set(value) = prefs.edit().putBoolean("amoled_mode", value).apply()

    /** Enable/disable the neon halo around the virtual cursor. */
    var neonEnabled: Boolean
        get() = prefs.getBoolean("neon_enabled", true)
        set(value) = prefs.edit().putBoolean("neon_enabled", value).apply()

    /** Neon intensity from 25 to 100 percent. */
    var neonIntensity: Int
        get() = prefs.getInt("neon_intensity", 75).coerceIn(25, 100)
        set(value) = prefs.edit().putInt("neon_intensity", value.coerceIn(25, 100)).apply()

    /** Cursor color stored as a stable palette key. */
    var cursorColorKey: String
        get() = prefs.getString("cursor_color", "pink") ?: "pink"
        set(value) = prefs.edit().putString("cursor_color", value).apply()

    var mouseSpeed: Float
        get() = prefs.getFloat("mouse_speed", 1.0f)
        set(value) = prefs.edit().putFloat("mouse_speed", value).apply()

    /** Scroll to the next video when the current one reaches its end. */
    var autoNext: Boolean
        get() = prefs.getBoolean("auto_next", false)
        set(value) = prefs.edit().putBoolean("auto_next", value).apply()

    /** Drop telemetry / ad requests (faster start on weak hardware). */
    var blockTrackers: Boolean
        get() = prefs.getBoolean("block_trackers", true)
        set(value) = prefs.edit().putBoolean("block_trackers", value).apply()

    /** Hide AV1 support from the page so TikTok serves H.264 (hardware decoded on most boxes). */
    var disableAv1: Boolean
        get() = prefs.getBoolean("disable_av1", true)
        set(value) = prefs.edit().putBoolean("disable_av1", value).apply()

    /** Scroll when the virtual cursor is held against the top/bottom screen edge. Off by default. */
    var edgeScroll: Boolean
        get() = prefs.getBoolean("edge_scroll", false)
        set(value) = prefs.edit().putBoolean("edge_scroll", value).apply()

    /** After a voice search, open the first result automatically so videos start playing. */
    var voiceAutoOpen: Boolean
        get() = prefs.getBoolean("voice_auto_open", true)
        set(value) = prefs.edit().putBoolean("voice_auto_open", value).apply()

    var startPage: StartPage
        get() = StartPage.fromKey(prefs.getString("start_page", null))
        set(value) = prefs.edit().putString("start_page", value.key).apply()
}
