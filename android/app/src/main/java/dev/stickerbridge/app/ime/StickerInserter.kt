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
import java.io.File

/**
 * Inserts a sticker into the focused field with commitContent.
 *
 * E1 (Instagram, Android): DM and Story fields advertise png/jpeg/gif, but accept
 * image/webp too; the comment field advertises nothing. So:
 * - a field that advertises no image MIME type gets no stickers at all;
 * - stickers are always sent as image/webp (static only until A2).
 */
object StickerInserter {
    const val STICKER_MIME = "image/webp"
    private const val OUTGOING_DIR = "outgoing"

    fun fieldAcceptsImages(info: EditorInfo?): Boolean {
        if (info == null) return false
        return EditorInfoCompat.getContentMimeTypes(info).any {
            it != null && ClipDescription.compareMimeTypes(it, "image/*")
        }
    }

    /** Copies the source file into app cache (background thread). Returns the cache file. */
    fun copyToCache(context: Context, source: Uri, id: Long): File {
        val dir = File(context.cacheDir, OUTGOING_DIR).apply { mkdirs() }
        // Keep the cache small: only the last few outgoing stickers are kept.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        val out = File(dir, "sticker_$id.webp")
        val input = context.contentResolver.openInputStream(source)
            ?: throw IllegalStateException("cannot open sticker")
        input.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        return out
    }

    /** Must run on the main thread with the current input connection. */
    fun commit(context: Context, ic: InputConnection, info: EditorInfo, file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val content = InputContentInfoCompat(uri, ClipDescription("sticker", arrayOf(STICKER_MIME)), null)
        return InputConnectionCompat.commitContent(
            ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
    }

    /** VP8X animation flag or ANIM chunk in the first bytes of a WEBP file. */
    fun isAnimatedWebp(file: File): Boolean = try {
        file.inputStream().use { s ->
            val head = ByteArray(64)
            val n = s.read(head)
            n >= 21 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(head, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                String(head, 12, 4, Charsets.US_ASCII) == "VP8X" &&
                (head[20].toInt() and 0x02) != 0
        }
    } catch (e: Exception) {
        false
    }
}
