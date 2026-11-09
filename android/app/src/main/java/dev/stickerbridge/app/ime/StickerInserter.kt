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
import dev.stickerbridge.app.convert.AnimatedStickerConverter
import dev.stickerbridge.app.convert.WebpHeader
import dev.stickerbridge.app.data.StickerGridItem
import java.io.File
import java.security.MessageDigest

/**
 * Inserts a sticker into the focused field with commitContent.
 *
 * E1 (Instagram, Android): DM and Story fields advertise png/jpeg/gif, accept
 * image/webp too, freeze animated WEBP and play GIF; the comment field advertises nothing. So:
 * - a field that advertises no image MIME type gets no stickers at all;
 * - static stickers are sent as image/webp;
 * - animated stickers are converted to GIF if the field advertises image/gif,
 *   otherwise the WEBP is sent as-is.
 */
object StickerInserter {
    const val WEBP_MIME = "image/webp"
    const val GIF_MIME = "image/gif"
    private const val OUTGOING_DIR = "outgoing"
    private const val GIF_DIR = "gif"
    private const val KEEP_OUTGOING = 10
    private const val KEEP_GIFS = 60

    fun fieldAcceptsImages(info: EditorInfo?): Boolean = advertised(info).any {
        ClipDescription.compareMimeTypes(it, "image/*")
    }

    fun fieldAcceptsGif(info: EditorInfo?): Boolean = advertised(info).any {
        ClipDescription.compareMimeTypes(GIF_MIME, it)
    }

    private fun advertised(info: EditorInfo?): List<String> =
        if (info == null) emptyList() else EditorInfoCompat.getContentMimeTypes(info).filterNotNull()

    /** Copies the source file into app cache (background thread). Returns the cache file. */
    fun copyToCache(context: Context, source: Uri, id: Long): File {
        val dir = File(context.cacheDir, OUTGOING_DIR).apply { mkdirs() }
        trim(dir, KEEP_OUTGOING)
        val out = File(dir, "sticker_$id.webp")
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

    /**
     * Returns a cached GIF for this sticker version, converting it if needed (background thread).
     * Cache key: documentId + lastModified, so a changed file is converted again.
     * Returns null if conversion is not possible (then the WEBP is sent).
     */
    fun gifFor(context: Context, item: StickerGridItem, webp: File): File? {
        val dir = File(File(context.cacheDir, OUTGOING_DIR), GIF_DIR).apply { mkdirs() }
        val gif = File(dir, cacheKey(item) + ".gif")
        if (gif.isFile && gif.length() > 0) {
            gif.setLastModified(System.currentTimeMillis())
            return gif
        }
        trim(dir, KEEP_GIFS)
        val result = try {
            AnimatedStickerConverter.convert(webp.readBytes(), gif)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
        return if (result != null && gif.isFile) gif else null
    }

    /** Must run on the main thread with the current input connection. */
    fun commit(context: Context, ic: InputConnection, info: EditorInfo, file: File, mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val content = InputContentInfoCompat(uri, ClipDescription("sticker", arrayOf(mime)), null)
        return InputConnectionCompat.commitContent(
            ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
    }

    private fun cacheKey(item: StickerGridItem): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${item.sourceUri}|${item.documentId}|${item.lastModified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Keeps the newest [keep] files in [dir] (directories are left alone). */
    private fun trim(dir: File, keep: Int) {
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
    }
}
