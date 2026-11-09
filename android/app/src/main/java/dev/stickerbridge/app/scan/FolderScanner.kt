package dev.stickerbridge.app.scan

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Result of listing one folder. [complete] is false if the listing failed or was cut short.
 * [childCount] counts every child row (also .nomedia and non-stickers), like [FolderScanner.stamp].
 */
data class FolderListing(
    val docs: List<ScannedDoc>,
    val complete: Boolean,
    val error: String? = null,
    val childCount: Int = 0,
)

/**
 * Lists a connected folder with ONE DocumentsContract children query and a minimal
 * projection (document id, mime, size, last modified). The display name is taken from the
 * document id (ExternalStorageProvider ids are "primary:<path>"), so it is not requested.
 *
 * Note: the storage provider computes size/time/type for every child no matter which
 * columns are requested, so ANY children query costs about as much as a full listing.
 * The keyboard-open check therefore uses only the folder's own last-modified time
 * ([quickStamp]), which is a single-row query.
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
                val iMime = c.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
                val iSize = c.getColumnIndexOrThrow(Document.COLUMN_SIZE)
                val iModified = c.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED)
                var rows = 0
                while (c.moveToNext()) {
                    rows++
                    val documentId = c.getString(iId) ?: continue
                    val name = nameFromDocumentId(documentId)
                    val mime = c.getString(iMime) ?: ""
                    if (mime == Document.MIME_TYPE_DIR || name.startsWith(".")) continue // .nomedia, folders
                    if (!isSticker(name, mime)) continue
                    docs += ScannedDoc(
                        documentId = documentId,
                        displayName = name,
                        mime = if (mime.isEmpty() || mime == "application/octet-stream") "image/webp" else mime,
                        sizeBytes = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                        lastModified = if (c.isNull(iModified)) 0L else c.getLong(iModified),
                    )
                }
                // If the cursor delivered fewer rows than it announced, treat the scan as partial.
                val complete = c.count < 0 || rows == c.count
                FolderListing(docs, complete, if (complete) null else "listing cut short", childCount = rows)
            }
        } catch (e: SecurityException) {
            FolderListing(emptyList(), complete = false, error = "access lost - reconnect the folder")
        } catch (e: Exception) {
            FolderListing(emptyList(), complete = false, error = e.javaClass.simpleName)
        }
    }

    /** The folder's own last-modified time (changes when files are added or removed), or 0. */
    fun folderLastModified(treeUri: Uri): Long = try {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        resolver.query(docUri, arrayOf(Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    /**
     * Keyboard-open fingerprint: only the folder's own last-modified time (one-row query,
     * a few ms). The directory time changes when files are added, removed or renamed.
     * childCount is -1 (not queried). Returns null if the folder can't be read.
     */
    fun quickStamp(treeUri: Uri): FolderStamp? {
        val modified = folderLastModified(treeUri)
        return if (modified > 0) FolderStamp(modified, -1) else null
    }

    /**
     * Full fingerprint: folder last-modified + number of children, querying only the
     * document id column (no per-file size/time). Returns null if the folder can't be read.
     */
    fun stamp(treeUri: Uri): FolderStamp? = try {
        val modified = folderLastModified(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val count = resolver.query(childrenUri, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count }
        if (count == null || count < 0) null else FolderStamp(modified, count)
    } catch (e: Exception) {
        null
    }

    private fun nameFromDocumentId(documentId: String): String =
        documentId.substringAfterLast('/').substringAfterLast(':')

    private fun isSticker(name: String, mime: String): Boolean =
        mime == "image/webp" || name.endsWith(".webp", ignoreCase = true)
}
