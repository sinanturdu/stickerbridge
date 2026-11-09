package dev.stickerbridge.app.switcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Debug-only logging for the keyboard-switch flow (tag "SB-Switch").
 * Logs step names and API levels only, never personal data. Silent in non-debuggable builds.
 *
 * Read with: adb logcat -s SB-Switch
 */
object SwitchLog {
    const val TAG = "SB-Switch"

    fun d(context: Context, message: String) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d(TAG, message)
    }
}
