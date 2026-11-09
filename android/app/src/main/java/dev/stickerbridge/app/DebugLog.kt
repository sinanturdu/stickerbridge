package dev.stickerbridge.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

/**
 * Debug-only logging. Only sizes, counts, timings and step names, never personal data
 * (no file names, no URIs, no typed text). Silent in non-debuggable builds.
 *
 * Tags: "SB-Gif" (animated sends), "SB-Scan" (folder scans), "SB-Switch" (keyboard switching).
 */
object DebugLog {
    const val GIF = "SB-Gif"
    const val SCAN = "SB-Scan"

    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun d(context: Context, tag: String, message: String) {
        if (isDebuggable(context)) Log.d(tag, message)
    }
}
