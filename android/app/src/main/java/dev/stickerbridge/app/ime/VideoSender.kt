package dev.stickerbridge.app.ime

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import dev.stickerbridge.app.convert.LruPolicy
import dev.stickerbridge.app.convert.StickerVideoEncoder
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
    private const val CACHE_VERSION = "v1"
    private const val PROFILE_TAG = "dm480"
    private const val MAX_CACHE_BYTES = 50L * 1024 * 1024

    /** Set to true if Instagram turns out to reject videos without an audio track (E8). */
    const val ADD_SILENT_AUDIO = false

    class Outcome(val file: File?, val result: StickerVideoEncoder.Result?, val fromCache: Boolean, val error: String?)

    /** Converts (or reuses the cached) MP4 and returns a unique outgoing copy. Background thread. */
    fun prepare(context: Context, item: StickerGridItem, webp: ByteArray): Outcome {
        return try {
            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            val cached = File(dir, "${CACHE_VERSION}_${PROFILE_TAG}_${StickerInserter.cacheKey(item)}.mp4")
            val fromCache = cached.isFile && cached.length() > 0
            var result: StickerVideoEncoder.Result? = null
            if (fromCache) {
                cached.setLastModified(System.currentTimeMillis()) // LRU touch
            } else {
                result = StickerVideoEncoder.encode(webp, cached, StickerVideoEncoder.Options(silentAudio = ADD_SILENT_AUDIO))
                if (result == null || !cached.isFile) return Outcome(null, result, false, "not decodable")
                trim(dir, keep = cached)
            }
            val out = StickerInserter.uniqueOutgoing(context, item.id, "mp4")
            cached.copyTo(out, overwrite = false)
            Outcome(out, result, fromCache, null)
        } catch (e: OutOfMemoryError) {
            Outcome(null, null, false, "out of memory")
        } catch (e: Exception) {
            Outcome(null, null, false, e.javaClass.simpleName)
        }
    }

    /**
     * Opens Instagram's share sheet for the video (FLAG_ACTIVITY_NEW_TASK: started from the
     * keyboard service). Falls back to the system chooser if Instagram is not installed.
     * Returns a short result for the log.
     */
    fun share(context: Context, file: File): String {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = VIDEO_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("sticker", uri) // carries the read grant
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent(send).setPackage(INSTAGRAM_PACKAGE))
            "instagram share sheet opened"
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "instagram not available, system chooser opened"
            } catch (e2: Exception) {
                "failed: ${e2.javaClass.simpleName}"
            }
        } catch (e: Exception) {
            "failed: ${e.javaClass.simpleName}"
        }
    }

    private fun trim(dir: File, keep: File) {
        val all = dir.listFiles()?.filter { it.isFile } ?: return
        all.filter { !it.name.startsWith(CACHE_VERSION + "_") }.forEach { it.delete() }
        val entries = all.filter { it.exists() && it.name.endsWith(".mp4") }.map { LruPolicy.Entry(it.name, it.length(), it.lastModified()) }
        LruPolicy.evict(entries, MAX_CACHE_BYTES, keep.name).forEach { File(dir, it).delete() }
    }
}
