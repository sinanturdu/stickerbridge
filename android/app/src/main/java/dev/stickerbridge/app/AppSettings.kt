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
}
