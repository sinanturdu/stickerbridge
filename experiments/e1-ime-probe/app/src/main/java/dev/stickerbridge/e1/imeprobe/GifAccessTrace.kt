package dev.stickerbridge.e1.imeprobe

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.FileObserver
import android.util.Log
import java.io.File

/** E9: only the bundled synthetic GIF, fixed codes and numbers. Never logs input objects. */
object GifAccessTrace {
    enum class Event {
        ATTEMPT, FILE_READY, PREPARE_ERROR, NO_CONNECTION, COMMIT_CALL, COMMIT_RESULT, COMMIT_ERROR,
        GET_TYPE_CALL, GET_TYPE_RETURN, GET_TYPE_ERROR,
        QUERY_CALL, QUERY_RETURN, QUERY_ERROR, OPEN_CALL, OPEN_RETURN, OPEN_ERROR,
        WATCH_STARTED, WATCH_ERROR, FS_ACCESS,
    }

    private val counts = mutableMapOf<Pair<Long, Event>, Long>()
    // Retain watches through the two-attempt session, even if the keyboard view is hidden.
    private val observers = mutableMapOf<Long, FileObserver>()

    fun enabled(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Persist only a synthetic sequence number, so process restarts do not reuse an outgoing URI. */
    @Synchronized
    fun begin(context: Context): Long {
        check(enabled(context))
        val prefs = context.getSharedPreferences("e9_synthetic_attempts", Context.MODE_PRIVATE)
        val previous = prefs.getLong("next", 0)
        check(previous < Long.MAX_VALUE)
        val attempt = previous + 1
        check(prefs.edit().putLong("next", attempt).commit())
        record(context, attempt, Event.ATTEMPT)
        return attempt
    }

    fun prepare(context: Context, attempt: Long): File {
        check(enabled(context) && attempt > 0)
        try {
            // Same basename, extension and provider root as E1; only the parent is fresh.
            val root = File(context.cacheDir, "stickers")
            check(root.isDirectory || root.mkdirs())
            val dir = File(root, "e9_$attempt")
            check(dir.mkdir())
            val out = File(dir, "test_gif.gif")
            context.resources.openRawResource(R.raw.test_gif).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            record(context, attempt, Event.FILE_READY, out.length())
            watch(context.applicationContext, attempt, out)
            return out
        } catch (e: Exception) {
            record(context, attempt, Event.PREPARE_ERROR)
            throw e
        }
    }

    @Synchronized
    @Suppress("DEPRECATION") // String constructor also supports the probe's API 26-28 devices.
    private fun watch(context: Context, attempt: Long, file: File) {
        // Passive filesystem notification only: never read the outgoing file or replace its FD.
        val observer = object : FileObserver(file.absolutePath, ACCESS) {
            override fun onEvent(event: Int, path: String?) {
                if (event and ACCESS != 0) {
                    // Event callbacks must not throw; do not inspect or log the callback path.
                    runCatching { record(context, attempt, Event.FS_ACCESS) }
                }
            }
        }
        observers[attempt] = observer
        runCatching { observer.startWatching() }
            .onSuccess { record(context, attempt, Event.WATCH_STARTED) }
            .onFailure {
                observers.remove(attempt)
                runCatching { observer.stopWatching() }
                record(context, attempt, Event.WATCH_ERROR)
            }
    }

    fun record(context: Context, uri: Uri, event: Event) {
        if (!enabled(context)) return
        // Classify only our fixed synthetic route. Do not emit strings derived from a URI.
        val parts = uri.pathSegments
        if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider" ||
            parts.size != 3 || parts[0] != "stickers" || parts[2] != "test_gif.gif") return
        val segment = parts[1]
        if (!segment.startsWith("e9_")) return
        val attempt = segment.removePrefix("e9_").toLongOrNull() ?: return
        if (attempt > 0 && segment == "e9_$attempt") record(context, attempt, event)
    }

    @Synchronized
    fun record(context: Context, attempt: Long, event: Event, value: Long = -1) {
        if (!enabled(context)) return
        val key = attempt to event
        val count = (counts[key] ?: 0) + 1
        counts[key] = count
        Log.d("SB-GifAccess", "event=${event.name} attempt=$attempt count=$count value=$value")
    }
}
