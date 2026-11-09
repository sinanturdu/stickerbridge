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
    private const val OUTGOING_DIR = "outgoing"
    private const val GIF_CACHE_DIR = "gifcache"
    private const val KEEP_GIF_CACHE = 60
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

    /** Copies the source sticker into a unique outgoing file (background thread). */
    fun copySource(context: Context, source: Uri, id: Long): File {
        val out = uniqueOutgoing(context, id, "webp")
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("cannot open sticker")
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    /** Header check (first 64 bytes) of a copied sticker. */
    fun isAnimatedWebp(file: File): Boolean = try {
        file.inputStream().use { s ->
            val head = ByteArray(WebpHeader.PROBE_BYTES)
            var n = 0
            while (n < head.size) {
                val r = s.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            WebpHeader.isAnimated(head, n)
        }
    } catch (e: Exception) {
        false
    }

    /** Outcome of preparing a GIF; [file] is a unique outgoing copy, or null on failure. */
    class GifOutcome(val file: File?, val result: AnimatedStickerConverter.Result?, val fromCache: Boolean, val error: String?)

    /**
     * Returns a unique outgoing GIF for this sticker (background thread). Uses the private
     * conversion cache (key: folder + documentId + lastModified), converting if needed.
     */
    fun prepareGif(context: Context, item: StickerGridItem, webp: File): GifOutcome {
        val cacheDir = File(context.cacheDir, GIF_CACHE_DIR).apply { mkdirs() }
        val cached = File(cacheDir, cacheKey(item) + ".gif")
        var result: AnimatedStickerConverter.Result? = null
        val fromCache = cached.isFile && cached.length() > 0
        if (fromCache) {
            cached.setLastModified(System.currentTimeMillis())
        } else {
            trim(cacheDir, KEEP_GIF_CACHE)
            result = try {
                AnimatedStickerConverter.convert(webp.readBytes(), cached)
            } catch (e: OutOfMemoryError) {
                return GifOutcome(null, null, false, "out of memory")
            } catch (e: Exception) {
                return GifOutcome(null, null, false, e.javaClass.simpleName)
            } ?: return GifOutcome(null, null, false, "not decodable")
        }
        val out = uniqueOutgoing(context, item.id, "gif")
        cached.copyTo(out, overwrite = false)
        keepDebugCopy(context, out, webp)
        return GifOutcome(out, result, fromCache, null)
    }

    /** Copies a ready-made GIF (debug size test) into a unique outgoing file. */
    fun prepareExistingGif(context: Context, id: Long, gif: File): File {
        val out = uniqueOutgoing(context, id, "gif")
        gif.copyTo(out, overwrite = false)
        return out
    }

    /** Debug builds only: <external files>/debug (pull with adb). Null in release builds. */
    fun debugDir(context: Context): File? {
        if (!DebugLog.isDebuggable(context)) return null
        return context.getExternalFilesDir("debug")?.apply { mkdirs() }
    }

    const val DEBUG_SOURCE_NAME = "last_source.webp"

    /** Must run on the main thread with the current input connection. */
    fun commit(context: Context, ic: InputConnection, info: EditorInfo, file: File, mime: String): Boolean {
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
    private fun keepDebugCopy(context: Context, gif: File, source: File) {
        val dir = debugDir(context) ?: return
        runCatching {
            gif.copyTo(File(dir, "last.gif"), overwrite = true)
            source.copyTo(File(dir, DEBUG_SOURCE_NAME), overwrite = true)
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

    /** Keeps the newest [keep] files of the private conversion cache. */
    private fun trim(dir: File, keep: Int) {
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }
}
