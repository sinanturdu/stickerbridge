package dev.stickerbridge.app

import android.content.Context

/** User settings (app-private SharedPreferences). */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_ANIMATE_PREVIEWS = "animate_previews"

    /** Play animated stickers in the keyboard grid (API 28+). Default on. */
    fun animatePreviews(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ANIMATE_PREVIEWS, true)

    fun setAnimatePreviews(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ANIMATE_PREVIEWS, on).apply()
    }

    private const val KEY_DEBUG_LINK_URI = "debug_link_uri"

    /** Debug builds: linkUri used by delivery variant B in the keyboard's Debug tab ("" = none). */
    fun debugLinkUri(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DEBUG_LINK_URI, "") ?: ""

    fun setDebugLinkUri(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DEBUG_LINK_URI, value.trim()).apply()
    }
}
