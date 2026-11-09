package dev.stickerbridge.app.ime

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import dev.stickerbridge.app.DebugLog
import dev.stickerbridge.app.convert.AnimatedStickerConverter
import dev.stickerbridge.app.convert.WebpHeader
import dev.stickerbridge.app.data.StickerGridItem
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Inserts a sticker into the focused field with commitContent.
 *
 * E1 (Instagram, Android): DM and Story fields advertise png/jpeg/gif, accept
 * image/webp too, freeze animated WEBP and play GIF; the comment field advertises nothing. So:
 * - a field that advertises no image MIME type gets no stickers at all;
 * - static stickers are sent as image/webp;
 * - animated stickers are converted to GIF if the field advertises image/gif,
 *   otherwise the WEBP is sent as-is.
 *
 * URI lifetime: the receiving app may read the content URI later (e.g. while uploading),
 * so every send gets a UNIQUE file in cache/outgoing/ (served by our FileProvider) that is
 * never overwritten or deleted during the session, and the URI permission is never released
 * by us. Files older than 24 h are removed at the next start ([cleanupOldOutgoing]).
 * Converted GIFs are also kept in a private conversion cache (cache/gifcache/, not served).
 */
object StickerInserter {
    const val WEBP_MIME = "image/webp"
    const val GIF_MIME = "image/gif"
    const val PNG_MIME = "image/png"
    private const val OUTGOING_DIR = "outgoing"
    private const val GIF_CACHE_DIR = "gifcache"
    /** Conversion cache limit (LRU). */
    const val MAX_GIF_CACHE_BYTES = 100L * 1024 * 1024
    private const val MAX_OUTGOING_AGE_MS = 24L * 60 * 60 * 1000

    fun fieldAcceptsImages(info: EditorInfo?): Boolean = advertised(info).any {
        ClipDescription.compareMimeTypes(it, "image/*")
    }

    fun fieldAcceptsGif(info: EditorInfo?): Boolean = advertised(info).any {
        ClipDescription.compareMimeTypes(GIF_MIME, it)
    }

    private fun advertised(info: EditorInfo?): List<String> =
        if (info == null) emptyList() else EditorInfoCompat.getContentMimeTypes(info).filterNotNull()

    /** Deletes outgoing files older than 24 h (call once per start, background thread). */
    fun cleanupOldOutgoing(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_OUTGOING_AGE_MS
        File(context.cacheDir, OUTGOING_DIR).walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) f.delete()
            else if (f.isDirectory && f.name != OUTGOING_DIR && f.list()?.isEmpty() == true) f.delete()
        }
    }

    /**
     * Reads the whole sticker from its SAF document URI (ContentResolver.openInputStream),
     * the same URI the thumbnails use. Background thread.
     * Throws [java.io.FileNotFoundException] if the file is no longer in the folder.
     */
    fun readSource(context: Context, source: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(source)
            ?: throw java.io.FileNotFoundException("provider returned no stream")
        return input.use { it.readBytes() }
    }

    /** Writes bytes to a unique outgoing file (the file that is shared with the receiving app). */
    fun writeOutgoing(context: Context, id: Long, bytes: ByteArray, ext: String): File {
        val out = uniqueOutgoing(context, id, ext)
        out.writeBytes(bytes)
        return out
    }

    /** Header check on the first bytes already read. */
    fun isAnimatedWebp(bytes: ByteArray): Boolean = WebpHeader.isAnimated(bytes, minOf(bytes.size, WebpHeader.PROBE_BYTES))

    /** Outcome of preparing a GIF; [file] is a unique outgoing copy, or null on failure. */
    class GifOutcome(val file: File?, val result: AnimatedStickerConverter.Result?, val fromCache: Boolean, val error: String?)

    /**
     * Returns a unique outgoing GIF for this sticker (background thread), converted from the
     * in-memory WEBP bytes. Uses the private conversion cache (key: folder + documentId +
     * lastModified). No intermediate file is read.
     */
    fun prepareGif(context: Context, item: StickerGridItem, webp: ByteArray): GifOutcome {
        return try {
            val fromCache = isGifCached(context, item)
            val (cached, result) = ensureCachedGif(context, item, webp)
            if (cached == null) return GifOutcome(null, result, false, if (result == null) "not decodable" else "output not written")
            val out = uniqueOutgoing(context, item.id, "gif")
            cached.copyTo(out, overwrite = false)
            keepDebugCopy(context, out, webp)
            GifOutcome(out, result, fromCache, null)
        } catch (e: OutOfMemoryError) {
            GifOutcome(null, null, false, "out of memory")
        } catch (e: Exception) {
            GifOutcome(null, null, false, e.javaClass.simpleName)
        }
    }

    /** True if a converted GIF for this sticker version is in the conversion cache. */
    fun isGifCached(context: Context, item: StickerGridItem): Boolean =
        gifCacheFile(context, item).let { it.isFile && it.length() > 0 }

    /**
     * Makes sure the conversion cache holds a GIF for this sticker version (background thread).
     * Returns the cached file (or null) and the conversion result (null when it was cached).
     * Also used by the background pre-converter.
     */
    fun ensureCachedGif(context: Context, item: StickerGridItem, webp: ByteArray): Pair<File?, AnimatedStickerConverter.Result?> {
        val cached = gifCacheFile(context, item)
        if (cached.isFile && cached.length() > 0) {
            cached.setLastModified(System.currentTimeMillis()) // LRU touch
            return cached to null
        }
        val result = AnimatedStickerConverter.convert(webp, cached) ?: return null to null
        trimToBytes(cached.parentFile!!, MAX_GIF_CACHE_BYTES, keep = cached)
        return (if (cached.isFile) cached else null) to result
    }

    private fun gifCacheFile(context: Context, item: StickerGridItem): File =
        File(File(context.cacheDir, GIF_CACHE_DIR).apply { mkdirs() }, cacheKey(item) + ".gif")

    /** LRU by last use (file time): deletes oldest GIFs until the cache is at most [maxBytes]. */
    private fun trimToBytes(dir: File, maxBytes: Long, keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gif") } ?: return
        val entries = files.map { dev.stickerbridge.app.convert.LruPolicy.Entry(it.name, it.length(), it.lastModified()) }
        for (name in dev.stickerbridge.app.convert.LruPolicy.evict(entries, maxBytes, keep.name)) File(dir, name).delete()
    }

    /**
     * Still image of the first frame as PNG (fallback when GIF conversion fails or the field
     * takes no GIF): animated WEBP is never sent, Instagram rejects it. Background thread.
     */
    fun prepareStill(context: Context, id: Long, webp: ByteArray): File? {
        val bitmap = firstFrame(webp) ?: return null
        return try {
            val out = uniqueOutgoing(context, id, "png")
            out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            out
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun firstFrame(webp: ByteArray): android.graphics.Bitmap? {
        // libwebp first (works on every API level), platform decoder as fallback.
        try {
            dev.stickerbridge.app.convert.WebpAnimDecoder.open(webp)?.use { dec ->
                val argb = IntArray(dec.width * dec.height)
                if (dec.next(argb)) {
                    return android.graphics.Bitmap.createBitmap(argb, dec.width, dec.height, android.graphics.Bitmap.Config.ARGB_8888)
                }
            }
        } catch (e: Throwable) {
            // fall through
        }
        return try {
            android.graphics.BitmapFactory.decodeByteArray(webp, 0, webp.size)
        } catch (e: Throwable) {
            null
        }
    }

    /** Debug builds only: <external files>/debug (pull with adb). Null in release builds. */
    fun debugDir(context: Context): File? {
        if (!DebugLog.isDebuggable(context)) return null
        return context.getExternalFilesDir("debug")?.apply { mkdirs() }
    }


    /** Must run on the main thread with the current input connection. */
    fun commit(
        context: Context,
        ic: InputConnection,
        info: EditorInfo,
        file: File,
        mime: String,
    ): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // The URI must end in the right extension and resolve to the same MIME type we
        // announce (the E1 probe GIF ended in .gif). Logged: suffix and types only.
        val resolvedType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val suffix = uri.lastPathSegment?.substringAfterLast('.', "")?.let { ".$it" } ?: "?"
        DebugLog.d(context, DebugLog.GIF, "commit uri suffix=$suffix resolverType=$resolvedType announced=$mime match=${resolvedType == mime}")
        val label = if (mime == GIF_MIME) "GIF" else "Sticker"
        val content = InputContentInfoCompat(uri, ClipDescription(label, arrayOf(mime)), null)
        // The grant is intentionally never released here (no releasePermission()): the
        // receiving app may read the URI after commitContent returns.
        return InputConnectionCompat.commitContent(
            ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
    }

    /**
     * Debug builds: the last converted GIF (debug/last.gif) and its animated WEBP source
     * (debug/last_source.webp, input for the "GIF size test").
     */
    private fun keepDebugCopy(context: Context, gif: File, source: ByteArray) {
        val dir = debugDir(context) ?: return
        runCatching {
            gif.copyTo(File(dir, "last.gif"), overwrite = true)
        }
    }

    private fun uniqueOutgoing(context: Context, id: Long, ext: String): File {
        val dir = File(context.cacheDir, OUTGOING_DIR).apply { mkdirs() }
        return File(dir, "s${id}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
    }

    private fun cacheKey(item: StickerGridItem): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${item.sourceUri}|${item.documentId}|${item.lastModified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

}
