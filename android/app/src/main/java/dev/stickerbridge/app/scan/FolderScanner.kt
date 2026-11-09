package dev.stickerbridge.app.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Result of listing one folder. [complete] is false if the listing failed or was cut short. */
data class FolderListing(val docs: List<ScannedDoc>, val complete: Boolean, val error: String? = null)

/**
 * Lists a connected folder with ONE DocumentsContract children query
 * (documentId, display name, mime, size, last modified). No per-file queries,
 * so ~12,000 files take well under a second on typical devices.
 */
class FolderScanner(private val resolver: ContentResolver) {

    fun list(treeUri: Uri): FolderListing {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        } catch (e: Exception) {
            return FolderListing(emptyList(), complete = false, error = "invalid folder")
        }
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        return try {
            val cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: return FolderListing(emptyList(), complete = false, error = "folder not readable")
            cursor.use { c ->
                val docs = ArrayList<ScannedDoc>(c.count.coerceAtLeast(0))
                val iId = c.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
                val iName = c.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
                val iMime = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
                val iModified = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                var rows = 0
                while (c.moveToNext()) {
                    rows++
                    val name = c.getString(iName) ?: continue
                    val mime = c.getString(iMime) ?: ""
                    if (mime == Document.MIME_TYPE_DIR || name.startsWith(".")) continue // .nomedia, folders
                    if (!isSticker(name, mime)) continue
                    docs += ScannedDoc(
                        documentId = c.getString(iId) ?: continue,
                        displayName = name,
                        mime = if (mime.isEmpty() || mime == "application/octet-stream") "image/webp" else mime,
                        sizeBytes = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                        lastModified = if (c.isNull(iModified)) 0L else c.getLong(iModified),
                    )
                }
                // If the cursor delivered fewer rows than it announced, treat the scan as partial.
                val complete = c.count < 0 || rows == c.count
                FolderListing(docs, complete, if (complete) null else "listing cut short")
            }
        } catch (e: SecurityException) {
            FolderListing(emptyList(), complete = false, error = "access lost - reconnect the folder")
        } catch (e: Exception) {
            FolderListing(emptyList(), complete = false, error = e.javaClass.simpleName)
        }
    }

    private fun isSticker(name: String, mime: String): Boolean =
        mime == "image/webp" || name.endsWith(".webp", ignoreCase = true)
}
