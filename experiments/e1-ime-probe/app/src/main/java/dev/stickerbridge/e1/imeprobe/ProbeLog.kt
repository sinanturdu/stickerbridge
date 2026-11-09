package dev.stickerbridge.e1.imeprobe

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only log in app-internal storage (filesDir/ime_probe_log.txt).
 *
 * Only metadata about the focused field and commitContent results is written here.
 * Typed text is never read by the IME and therefore can never end up in this log.
 */
object ProbeLog {
    private const val TAG = "ImeProbe"
    private const val FILE_NAME = "ime_probe_log.txt"
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    fun append(context: Context, message: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$stamp] $message"
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                file(context).appendText(line + "\n")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write log file", e)
            }
        }
    }

    fun read(context: Context): String = synchronized(lock) {
        val f = file(context)
        if (f.exists()) f.readText() else ""
    }

    fun clear(context: Context) = synchronized(lock) {
        file(context).delete()
        Unit
    }
}
