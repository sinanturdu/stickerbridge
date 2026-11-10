package dev.stickerbridge.app.ime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.stickerbridge.app.convert.LruPolicy
import dev.stickerbridge.app.convert.StickerVideoEncoder
import dev.stickerbridge.app.convert.VideoDiagnostics
import dev.stickerbridge.app.convert.VideoFiles
import dev.stickerbridge.app.data.StickerGridItem
import java.io.File

/**
 * E8: sends an animated sticker to Instagram DMs as a short MP4 through Instagram's share
 * sheet (ACTION_SEND), because DMs drop GIFs inserted by keyboards (E7).
 *
 * The MP4 is cached like the GIFs (key: cache version + profile + folder/documentId/
 * lastModified, LRU) and every send uses a unique outgoing copy served by our FileProvider.
 */
object VideoSender {
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val VIDEO_MIME = "video/mp4"

    private const val CACHE_DIR = "videocache"
    private const val CACHE_VERSION = "v2" // Explicit MP4 end timestamp and validated publication.
    private const val PROFILE_TAG = "dm480"
    private const val MAX_CACHE_BYTES = 50L * 1024 * 1024

    /** Set to true if Instagram turns out to reject videos without an audio track (E8). */
    const val ADD_SILENT_AUDIO = false

    class Outcome(val file: File?, val result: StickerVideoEncoder.Result?, val fromCache: Boolean, val error: String?)

    /** Converts (or reuses the cached) MP4 and returns a unique outgoing copy. Background thread. */
    @Synchronized // Cancelled synchronous encodes may still run; do not race cache reads/writes/trim.
    fun prepare(context: Context, item: StickerGridItem, webp: ByteArray, trace: VideoDiagnostics = VideoDiagnostics()): Outcome {
        var outgoing: File? = null
        return try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            val cached = File(dir, "${CACHE_VERSION}_${PROFILE_TAG}_${StickerInserter.cacheKey(item)}.mp4")
            val fromCache = cached.isFile && cached.length() > 0
            trace.step(VideoDiagnostics.Stage.CACHE, "hit" to if (fromCache) 1 else 0)
            var result: StickerVideoEncoder.Result? = null
            if (fromCache) {
                try {
                    VideoFiles.validate(cached, trace)
                } catch (e: Exception) {
                    cached.delete() // Fail this attempt; the next explicit retry can regenerate it.
                    throw e
                }
                cached.setLastModified(System.currentTimeMillis()) // LRU touch
            } else {
                result = StickerVideoEncoder.encode(webp, cached, StickerVideoEncoder.Options(silentAudio = ADD_SILENT_AUDIO), trace)
                if (result == null) {
                    trace.failure(IllegalArgumentException())
                    return Outcome(null, null, false, "DECODE")
                }
                trim(dir, keep = cached)
            }
            trace.step(VideoDiagnostics.Stage.COPY, "source_bytes" to cached.length())
            val out = StickerInserter.uniqueOutgoing(context, item.id, "mp4")
            outgoing = out
            cached.copyTo(out, overwrite = false)
            check(out.isFile && out.length() == cached.length() && out.length() > 0)
            trace.step(VideoDiagnostics.Stage.COPY, "complete" to 1, "bytes" to out.length(),
                "millis" to (result?.millis ?: 0))
            Outcome(out, result, fromCache, null)
        } catch (e: OutOfMemoryError) {
            outgoing?.delete()
            trace.failure(e)
            Outcome(null, null, false, "OOM")
        } catch (e: Exception) {
            outgoing?.delete()
            trace.failure(e)
            Outcome(null, null, false, "PREPARE")
        }
    }

    /**
     * Opens Instagram's share sheet for the video (FLAG_ACTIVITY_NEW_TASK: started from the
     * keyboard service). Falls back to the system chooser if Instagram is not installed.
     * Returns a short result for the log.
     */
    fun share(context: Context, file: File, trace: VideoDiagnostics = VideoDiagnostics()): String {
        return try {
            trace.step(VideoDiagnostics.Stage.URI, "bytes" to file.length())
            check(file.isFile && file.length() > 0)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mimeMatches = context.contentResolver.getType(uri) == VIDEO_MIME
            val readable = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize == file.length() } == true
            trace.step(VideoDiagnostics.Stage.URI, "mime_match" to if (mimeMatches) 1 else 0,
                "readable" to if (readable) 1 else 0)
            check(mimeMatches && readable)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = VIDEO_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("sticker", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            trace.step(VideoDiagnostics.Stage.HANDOFF, "read_grant" to 1, "clip_items" to send.clipData!!.itemCount)
            try {
                context.startActivity(Intent(send).setPackage(INSTAGRAM_PACKAGE))
                trace.step(VideoDiagnostics.Stage.HANDOFF, "launched" to 1)
                "INSTAGRAM_OPENED"
            } catch (e: ActivityNotFoundException) {
                trace.step(VideoDiagnostics.Stage.CHOOSER)
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                trace.step(VideoDiagnostics.Stage.CHOOSER, "launched" to 1)
                "CHOOSER_OPENED"
            }
        } catch (e: Exception) {
            trace.failure(e)
            "FAILED"
        }
    }

    private fun trim(dir: File, keep: File) {
        val all = dir.listFiles()?.filter { it.isFile } ?: return
        all.filter { !it.name.startsWith(CACHE_VERSION + "_") }.forEach { it.delete() }
        val entries = all.filter { it.exists() && it.name.endsWith(".mp4") }.map { LruPolicy.Entry(it.name, it.length(), it.lastModified()) }
        LruPolicy.evict(entries, MAX_CACHE_BYTES, keep.name).forEach { File(dir, it).delete() }
    }
}
