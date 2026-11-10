package dev.stickerbridge.e1.imeprobe

import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import dev.stickerbridge.e1.imeprobe.GifAccessTrace.Event

/** Debug source set only. Returns the original cursor/descriptor, with no pipe or read proxy. */
class GifDiagnosticFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? = observe(
        uri, Event.GET_TYPE_CALL, Event.GET_TYPE_RETURN, Event.GET_TYPE_ERROR,
    ) { super.getType(uri) }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = observe(uri, Event.QUERY_CALL, Event.QUERY_RETURN, Event.QUERY_ERROR) {
        super.query(uri, projection, selection, selectionArgs, sortOrder)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = observe(
        uri, Event.OPEN_CALL, Event.OPEN_RETURN, Event.OPEN_ERROR,
    ) { super.openFile(uri, mode) }

    private inline fun <T> observe(
        uri: Uri, call: Event, returned: Event, error: Event, operation: () -> T,
    ): T {
        context?.let { GifAccessTrace.record(it, uri, call) }
        try {
            val result = operation()
            context?.let { GifAccessTrace.record(it, uri, returned) }
            return result
        } catch (e: Exception) {
            context?.let { GifAccessTrace.record(it, uri, error) }
            throw e // Same exception, no message/class/caller/arguments logged.
        }
    }
}
